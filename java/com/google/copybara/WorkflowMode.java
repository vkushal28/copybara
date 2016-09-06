/*
 * Copyright (C) 2016 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.copybara;

import static com.google.copybara.WorkflowOptions.CHANGE_REQUEST_PARENT_FLAG;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.UnmodifiableIterator;
import com.google.copybara.Destination.WriterResult;
import com.google.copybara.Origin.ChangesVisitor;
import com.google.copybara.Origin.Reference;
import com.google.copybara.Origin.VisitResult;
import com.google.copybara.doc.annotations.DocField;
import com.google.copybara.util.console.ProgressPrefixConsole;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModule;
import com.google.devtools.build.lib.syntax.SkylarkList;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Workflow type to run between origin an destination
 */
public enum WorkflowMode {
  /**
   * Create a single commit in the destination with new tree state.
   */
  @DocField(description = "Create a single commit in the destination with new tree state.")
  SQUASH {
    @Override
    <R extends Origin.Reference> void run(Workflow<R>.RunHelper runHelper)
        throws RepoException, IOException, ValidationException {

      runHelper.migrate(
          runHelper.getResolvedRef(),
          runHelper.getConsole(),
          new Metadata("Project import generated by Copybara (go/copybara).\n",
              // SQUASH workflows always use the default author
              runHelper.getAuthoring().getDefaultAuthor()),
          new LazyChangesForSquash<>(runHelper));
    }
  },

  /**
   * Import each origin change individually.
   */
  @DocField(description = "Import each origin change individually.")
  ITERATIVE {
    @Override
    <R extends Origin.Reference> void run(Workflow<R>.RunHelper runHelper)
        throws RepoException, IOException, ValidationException {
      ImmutableList<Change<R>> changes = runHelper.changesSinceLastImport();
      int changeNumber = 1;
      UnmodifiableIterator<Change<R>> changesIterator = changes.iterator();
      Deque<Change<R>> migrated = new ArrayDeque<>();
      while (changesIterator.hasNext()) {
        Change<R> change = changesIterator.next();
        String prefix = String.format(
            "Change %d of %d (%s): ",
            changeNumber, changes.size(), change.getReference().asString());
        WriterResult result;
        try {
          result = runHelper.migrate(
              change.getReference(),
              new ProgressPrefixConsole(prefix, runHelper.getConsole()),
              new Metadata(change.getMessage(), change.getAuthor()),
              new ComputedChanges(ImmutableList.of(change), migrated));
        } catch (EmptyChangeException e) {
          runHelper.getConsole().warn(e.getMessage());
          result = WriterResult.OK;
        }
        migrated.addFirst(change);

        if (result == WriterResult.PROMPT_TO_CONTINUE && changesIterator.hasNext()) {
          // Use the regular console to log prompt and final message, it will be easier to spot
          if (!runHelper.getConsole()
              .promptConfirmation("Continue importing next change?")) {
            String message = String.format("Iterative workflow aborted by user after: %s", prefix);
            runHelper.getConsole().warn(message);
            throw new ChangeRejectedException(message);
          }
        }
        changeNumber++;
      }
    }
  },
  @DocField(description = "Import an origin tree state diffed by a common parent"
      + " in destination. This could be a GH Pull Request, a Gerrit Change, etc.")
  CHANGE_REQUEST {
    @Override
    <R extends Origin.Reference> void run(Workflow<R>.RunHelper runHelper)
        throws RepoException, IOException, ValidationException {
      final AtomicReference<String> requestParent = new AtomicReference<>(
          runHelper.workflowOptions().changeBaseline);
      final String originLabelName = runHelper.getDestination().getLabelNameWhenOrigin();
      if (Strings.isNullOrEmpty(requestParent.get())) {
        runHelper.getReader().visitChanges(runHelper.getResolvedRef(), new ChangesVisitor() {
          @Override
          public VisitResult visit(Change<?> change) {
            if (change.getLabels().containsKey(originLabelName)) {
              requestParent.set(change.getLabels().get(originLabelName));
              return VisitResult.TERMINATE;
            }
            return VisitResult.CONTINUE;
          }
        });
      }

      if (Strings.isNullOrEmpty(requestParent.get())) {
        throw new ValidationException(
            "Cannot find matching parent commit in in the destination. Use '"
                + CHANGE_REQUEST_PARENT_FLAG
                + "' flag to force a parent commit to use as baseline in the destination.");
      }
      Change<R> change = runHelper.getReader().change(runHelper.getResolvedRef());
      runHelper.migrate(
          runHelper.getResolvedRef(),
          runHelper.getConsole(),
          new Metadata(change.getMessage(), change.getAuthor()),
          new ComputedChanges(ImmutableList.of(change), ImmutableList.<Change<?>>of()),
          requestParent.get());
    }
  },

  // TODO(copybara-team): Implement
  @SuppressWarnings("unused")
  @DocField(description = "Mirror individual changes from origin to destination. Requires that "
      + "origin and destination are of the same type and that they support mirroring.",
      undocumented = true)
  MIRROR {
    @Override
    <R extends Origin.Reference> void run(Workflow<R>.RunHelper helper)
        throws RepoException, IOException, ValidationException {
      throw new UnsupportedOperationException("WorkflowMode 'MIRROR' not implemented.");
    }
  };

  private static final Logger logger = Logger.getLogger(WorkflowMode.class.getName());

  abstract <R extends Origin.Reference> void run(Workflow<R>.RunHelper runHelper)
      throws RepoException, IOException, ValidationException;

  /**
   * An implementation of {@link Changes} that compute the list of changes lazily. Only when
   * a transformer request it.
   */
  @SkylarkModule(name = "LazyChanges", doc = "Lazy changes implementation", documented = false)
  private static class LazyChangesForSquash<R extends Reference> extends Changes {

    private final Workflow<R>.RunHelper runHelper;
    private SkylarkList<? extends Change<?>> cached;

    private LazyChangesForSquash(Workflow<R>.RunHelper runHelper) {
      this.runHelper = runHelper;
      cached = null;
    }

    @Override
    public synchronized SkylarkList<? extends Change<?>> getCurrent() {
      if (cached == null) {
        try {
          // Reverse since changesSinceLastImport returns the first commit to import
          // first.
          cached = SkylarkList.createImmutable(runHelper.changesSinceLastImport().reverse());
        } catch (RepoException e) {
          logger.log(Level.WARNING, "Previous reference couldn't be resolved."
              + " Cannot compute the set of changes in the migration");
          cached = SkylarkList.createImmutable(ImmutableList.<Change<?>>of());
        }
      }
      return cached;
    }

    @Override
    public SkylarkList<? extends Change<?>> getMigrated() {
      return SkylarkList.createImmutable(ImmutableList.<Change<?>>of());
    }
  }

  @SkylarkModule(name = "ComputedChanges", doc = "Compyted changes implementation",
      documented = false)
  private static class ComputedChanges extends Changes {

    private final SkylarkList<? extends Change<?>> current;
    private final SkylarkList<? extends Change<?>> migrated;

    private ComputedChanges(Iterable<? extends Change<?>> current,
        Iterable<? extends Change<?>> migrated) {
      this.current = SkylarkList.createImmutable(current);
      this.migrated = SkylarkList.createImmutable(migrated);
    }

    @Override
    public SkylarkList<? extends Change<?>> getCurrent() {
      return current;
    }

    @Override
    public SkylarkList<? extends Change<?>> getMigrated() {
      return migrated;
    }
  }
}
