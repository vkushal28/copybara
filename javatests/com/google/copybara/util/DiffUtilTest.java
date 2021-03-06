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

package com.google.copybara.util;

import static com.google.common.truth.Truth.assertThat;
import static com.google.copybara.testing.FileSubjects.assertThatPath;

import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class DiffUtilTest {

  private static final int STRIP_SLASHES = 2;
  private static final boolean VERBOSE = true;
  private static final ImmutableList<String> NO_EXCLUDED = ImmutableList.of();

  // Command requires the working dir as a File, and Jimfs does not support Path.toFile()
  @Rule public final TemporaryFolder tmpFolder = new TemporaryFolder();

  @Rule public final ExpectedException thrown = ExpectedException.none();

  private Path left;
  private Path right;
  private Path destination;

  @Before
  public void setUp() throws Exception {
    Path rootPath = tmpFolder.getRoot().toPath();
    left = createDir(rootPath, "left");
    right = createDir(rootPath, "right");
    destination = createDir(rootPath, "destination");
  }

  @Test
  public void pathsAreNotSiblings() throws Exception {
    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("Paths 'one' and 'other' must be sibling directories");

    Path foo = createDir(left, "foo");
    DiffUtil.diff(left, foo, VERBOSE);
  }

  @Test
  public void emptyDiff() throws Exception {
    writeFile(left, "file1.txt", "foo");
    writeFile(left, "b/file2.txt", "bar");
    writeFile(right, "file1.txt", "foo");
    writeFile(right, "b/file2.txt", "bar");

    byte[] diffContents = DiffUtil.diff(left, right, VERBOSE);

    assertThat(diffContents).isEmpty();
  }

  /**
   * Don't treat origin/destination folders as flags or other special argument. This means that
   * we run 'git options -- origin dest' instead of 'git options origin dest' that is
   * ambiguous.
   */
  @Test
  public void originDestinationFolderSeparatedArguments() throws Exception {
    // Should not be treated as an illegal flag
    left = createDir(tmpFolder.getRoot().toPath(), "-foo");
    right = createDir(tmpFolder.getRoot().toPath(), "reverse");
    writeFile(left, "file1.txt", "foo");
    writeFile(right, "file1.txt", "foo");

    assertThat(DiffUtil.diff(left, right, VERBOSE)).isEmpty();
  }

  @Test
  public void apply() throws Exception {
    writeFile(left, "file1.txt", "foo");
    writeFile(left, "b/file2.txt", "bar");
    writeFile(right, "file1.txt", "new foo");
    writeFile(right, "c/file3.txt", "bar");
    writeFile(destination, "file1.txt", "foo");
    writeFile(destination, "b/file2.txt", "bar");

    byte[] diffContents = DiffUtil.diff(left, right, VERBOSE);

    DiffUtil.patch(destination, diffContents, NO_EXCLUDED, STRIP_SLASHES, VERBOSE, /*reverse=*/ false);

    assertThatPath(left)
        .containsFile("file1.txt", "foo")
        .containsFile("b/file2.txt", "bar")
        .containsNoMoreFiles();
    assertThatPath(right)
        .containsFile("file1.txt", "new foo")
        .containsFile("c/file3.txt", "bar")
        .containsNoMoreFiles();
    assertThatPath(destination)
        .containsFile("file1.txt", "new foo")
        .containsFile("c/file3.txt", "bar")
        .containsNoMoreFiles();

    DiffUtil.patch(destination, diffContents, NO_EXCLUDED, STRIP_SLASHES, VERBOSE, /*reverse=*/ true);
    assertThatPath(destination)
        .containsFile("file1.txt", "foo")
        .containsFile("b/file2.txt", "bar")
        .containsNoMoreFiles();
  }

  @Test
  public void applyExcluded() throws Exception {
    writeFile(left, "file1.txt", "foo");
    writeFile(left, "excluded/file2.txt", "bar");
    writeFile(left, "other_excluded/file3.txt", "bar");
    writeFile(right, "file1.txt", "new foo");
    writeFile(right, "excluded/file2.txt", "new bar");
    writeFile(right, "other_excluded/file3.txt", "new bar");
    writeFile(destination, "file1.txt", "foo");
    writeFile(destination, "excluded/file2.txt", "bar");
    writeFile(destination, "other_excluded/file3.txt", "bar");
    ImmutableList<String> excludedPaths = ImmutableList.of("excluded/*", "other_excluded/*");

    byte[] diffContents = DiffUtil.diff(left, right, VERBOSE);

    DiffUtil.patch(
        destination, diffContents, excludedPaths, STRIP_SLASHES, VERBOSE, /*reverse=*/ false);

    assertThatPath(left)
        .containsFile("file1.txt", "foo")
        .containsFile("excluded/file2.txt", "bar")
        .containsFile("other_excluded/file3.txt", "bar")
        .containsNoMoreFiles();
    assertThatPath(right)
        .containsFile("file1.txt", "new foo")
        .containsFile("excluded/file2.txt", "new bar")
        .containsFile("other_excluded/file3.txt", "new bar")
        .containsNoMoreFiles();
    assertThatPath(destination)
        .containsFile("file1.txt", "new foo")
        .containsFile("excluded/file2.txt", "bar")
        .containsFile("other_excluded/file3.txt", "bar")
        .containsNoMoreFiles();

    DiffUtil.patch(
        destination, diffContents, excludedPaths, STRIP_SLASHES, VERBOSE, /*reverse=*/ true);
    assertThatPath(destination)
        .containsFile("file1.txt", "foo")
        .containsFile("excluded/file2.txt", "bar")
        .containsFile("other_excluded/file3.txt", "bar")
        .containsNoMoreFiles();
  }

  /**
   * Tests the situation where the destination is ahead of the baseline, and the diff between the
   * baseline and the destination can be applied without conflicts to the destination.
   */
  @Test
  public void applyDifferentBaseline() throws Exception {
    writeFile(left, "file1.txt", "foo\n"
        + "more foo\n");
    writeFile(left, "b/file2.txt", "bar");
    writeFile(left, "file5.txt", "mmm\n"
        + "zzzzzzzzz\n"
        + "zzzzzzzzzzzzzz\n"
        + "zzzzzzzzzzzzzzzzzzzz\n"
        + "bar\n"
        + "foo\n"
        + "bar");
    writeFile(right, "file1.txt", "new foo\n"
        + "more foo\n");
    writeFile(right, "c/file3.txt", "bar");
    writeFile(right, "file5.txt", "mmm\n"
        + "zzzzzzzzz\n"
        + "zzzzzzzzzzzzzz\n"
        + "zzzzzzzzzzzzzzzzzzzz\n"
        + "bar\n"
        + "xxx\n"
        + "bar");
    writeFile(destination, "file1.txt", "foo\n"
        + "more foo\n"
        + "added foo\n");
    writeFile(destination, "b/file2.txt", "bar");
    writeFile(destination, "c/file4.txt", "bar");
    writeFile(destination, "file5.txt", "vvv\n"
        + "zzzzzzzzz\n"
        + "zzzzzzzzzzzzzz\n"
        + "zzzzzzzzzzzzzzzzzzzz\n"
        + "bar\n"
        + "foo\n"
        + "bar");

    byte[] diffContents = DiffUtil.diff(left, right, VERBOSE);

    DiffUtil.patch(
        destination, diffContents, NO_EXCLUDED, STRIP_SLASHES, VERBOSE, /*reverse=*/ false);

    assertThatPath(destination)
        .containsFile("file1.txt", "new foo\n"
            + "more foo\n"
            + "added foo\n")
        .containsFile("c/file3.txt", "bar")
        .containsFile("c/file4.txt", "bar")
        .containsFile("file5.txt", "vvv\n"
            + "zzzzzzzzz\n"
            + "zzzzzzzzzzzzzz\n"
            + "zzzzzzzzzzzzzzzzzzzz\n"
            + "bar\n"
            + "xxx\n"
            + "bar")
        .containsNoMoreFiles();
  }

  @Test
  public void applyEmptyDiff() throws Exception {
    writeFile(left, "file1.txt", "foo");
    writeFile(left, "b/file2.txt", "bar");
    DiffUtil.patch(
        left, /*empty diff*/ new byte[]{}, NO_EXCLUDED, STRIP_SLASHES, VERBOSE, /*reverse=*/ false);

    assertThatPath(left)
        .containsFile("file1.txt", "foo")
        .containsFile("b/file2.txt", "bar")
        .containsNoMoreFiles();
  }

  @Test
  public void applyFails() throws Exception {
    writeFile(left, "file1.txt", "foo");
    writeFile(right, "file1.txt", "new foo\n");
    writeFile(destination, "file1.txt", "foo\nmore foo\n");

    thrown.expect(IOException.class);
    thrown.expectMessage("error: patch failed: file1.txt:1\n"
        + "error: file1.txt: patch does not apply");

    byte[] diffContents = DiffUtil.diff(left, right, VERBOSE);
    DiffUtil.patch(
        destination, diffContents, NO_EXCLUDED, STRIP_SLASHES, VERBOSE, /*reverse=*/ false);
  }

  @Test
  public void negativeSlashesFail() throws Exception {
    thrown.expect(IllegalArgumentException.class);
    thrown.expectMessage("stripSlashes must be >= 0");
    DiffUtil.patch(
        destination, new byte[1], NO_EXCLUDED, /*stripSlashes=*/ -1, VERBOSE, /*reverse=*/ false);
  }

  private Path createDir(Path parent, String name) throws IOException {
    Path path = parent.resolve(name);
    Files.createDirectories(path);
    return path;
  }

  private void writeFile(Path parent, String fileName, String fileContents) throws IOException {
    Path filePath = parent.resolve(fileName);
    Files.createDirectories(filePath.getParent());
    Files.write(parent.resolve(filePath), fileContents.getBytes(StandardCharsets.UTF_8));
  }
}
