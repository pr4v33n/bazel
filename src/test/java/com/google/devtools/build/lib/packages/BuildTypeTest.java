// Copyright 2015 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.packages;

import static com.google.common.truth.Truth.assertThat;
import static com.google.devtools.build.lib.testutil.MoreAsserts.assertSameContents;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.packages.BuildType.Selector;
import com.google.devtools.build.lib.syntax.EvalUtils;
import com.google.devtools.build.lib.syntax.Printer;
import com.google.devtools.build.lib.syntax.SelectorList;
import com.google.devtools.build.lib.syntax.SelectorValue;
import com.google.devtools.build.lib.syntax.Type.ConversionException;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Test of type-conversions for build-specific types.
 */
@RunWith(JUnit4.class)
public class BuildTypeTest {
  private Label currentRule;

  @Before
  public void setUp() throws Exception {
    this.currentRule = Label.parseAbsolute("//quux:baz");
  }

  @Test
  public void testFilesetEntry() throws Exception {
    Label srcDir = Label.create("foo", "src");
    Label entryLabel = Label.create("foo", "entry");
    FilesetEntry input =
        new FilesetEntry(srcDir, ImmutableList.of(entryLabel), null, null, null, null);
    assertEquals(input, BuildType.FILESET_ENTRY.convert(input, null, currentRule));
    assertThat(BuildType.FILESET_ENTRY.flatten(input)).containsExactly(entryLabel);
  }

  @Test
  public void testFilesetEntryList() throws Exception {
    Label srcDir = Label.create("foo", "src");
    Label entry1Label = Label.create("foo", "entry1");
    Label entry2Label = Label.create("foo", "entry");
    List<FilesetEntry> input = ImmutableList.of(
        new FilesetEntry(srcDir, ImmutableList.of(entry1Label), null, null, null, null),
        new FilesetEntry(srcDir, ImmutableList.of(entry2Label), null, null, null, null));
    assertEquals(input, BuildType.FILESET_ENTRY_LIST.convert(input, null, currentRule));
    assertThat(BuildType.FILESET_ENTRY_LIST.flatten(input)).containsExactly(entry1Label, entry2Label);
  }

  /**
   * Tests basic {@link Selector} functionality.
   */
  @Test
  public void testSelector() throws Exception {
    Object input = ImmutableMap.of(
        "//conditions:a", "//a:a",
        "//conditions:b", "//b:b",
        BuildType.Selector.DEFAULT_CONDITION_KEY, "//d:d");
    Selector<Label> selector = new Selector<>(input, null, currentRule, BuildType.LABEL);
    assertEquals(BuildType.LABEL, selector.getOriginalType());

    Map<Label, Label> expectedMap = ImmutableMap.of(
        Label.parseAbsolute("//conditions:a"), Label.create("a", "a"),
        Label.parseAbsolute("//conditions:b"), Label.create("b", "b"),
        Label.parseAbsolute(BuildType.Selector.DEFAULT_CONDITION_KEY), Label.create("d", "d"));
    assertSameContents(expectedMap.entrySet(), selector.getEntries().entrySet());
  }

  /**
   * Tests that creating a {@link Selector} over a mismatching native type triggers an
   * exception.
   */
  @Test
  public void testSelectorWrongType() throws Exception {
    Object input = ImmutableMap.of(
        "//conditions:a", "not a label",
        "//conditions:b", "also not a label",
        BuildType.Selector.DEFAULT_CONDITION_KEY, "whatever");
    try {
      new Selector<Label>(input, null, currentRule, BuildType.LABEL);
      fail("Expected Selector instantiation to fail since the input isn't a selection of labels");
    } catch (ConversionException e) {
      assertThat(e.getMessage()).contains("invalid label 'not a label'");
    }
  }

  /**
   * Tests that non-label selector keys trigger an exception.
   */
  @Test
  public void testSelectorKeyIsNotALabel() throws Exception {
    Object input = ImmutableMap.of(
        "not a label", "//a:a",
        BuildType.Selector.DEFAULT_CONDITION_KEY, "whatever");
    try {
      new Selector<Label>(input, null, currentRule, BuildType.LABEL);
      fail("Expected Selector instantiation to fail since the key isn't a label");
    } catch (ConversionException e) {
      assertThat(e.getMessage()).contains("invalid label 'not a label'");
    }
  }

  /**
   * Tests that {@link Selector} correctly references its default value.
   */
  @Test
  public void testSelectorDefault() throws Exception {
    Object input = ImmutableMap.of(
        "//conditions:a", "//a:a",
        "//conditions:b", "//b:b",
        BuildType.Selector.DEFAULT_CONDITION_KEY, "//d:d");
    assertEquals(
        Label.create("d", "d"),
        new Selector<Label>(input, null, currentRule, BuildType.LABEL).getDefault());
  }

  @Test
  public void testSelectorList() throws Exception {
    Object selector1 = new SelectorValue(ImmutableMap.of("//conditions:a",
        ImmutableList.of("//a:a"), "//conditions:b", ImmutableList.of("//b:b")));
    Object selector2 = new SelectorValue(ImmutableMap.of("//conditions:c",
        ImmutableList.of("//c:c"), "//conditions:d", ImmutableList.of("//d:d")));
    BuildType.SelectorList<List<Label>> selectorList = new BuildType.SelectorList<>(
        ImmutableList.of(selector1, selector2), null, currentRule, BuildType.LABEL_LIST);

    assertEquals(BuildType.LABEL_LIST, selectorList.getOriginalType());
    assertSameContents(
        ImmutableSet.of(
            Label.parseAbsolute("//conditions:a"), Label.parseAbsolute("//conditions:b"),
            Label.parseAbsolute("//conditions:c"), Label.parseAbsolute("//conditions:d")),
        selectorList.getKeyLabels());

    List<Selector<List<Label>>> selectors = selectorList.getSelectors();
    assertSameContents(
        ImmutableMap.of(
                Label.parseAbsolute("//conditions:a"), ImmutableList.of(Label.create("a", "a")),
                Label.parseAbsolute("//conditions:b"), ImmutableList.of(Label.create("b", "b")))
            .entrySet(),
        selectors.get(0).getEntries().entrySet());
    assertSameContents(
        ImmutableMap.of(
            Label.parseAbsolute("//conditions:c"), ImmutableList.of(Label.create("c", "c")),
            Label.parseAbsolute("//conditions:d"), ImmutableList.of(Label.create("d", "d")))
            .entrySet(),
        selectors.get(1).getEntries().entrySet());
  }

  @Test
  public void testSelectorListMixedTypes() throws Exception {
    Object selector1 =
        new SelectorValue(ImmutableMap.of("//conditions:a", ImmutableList.of("//a:a")));
    Object selector2 =
        new SelectorValue(ImmutableMap.of("//conditions:b", "//b:b"));
    try {
      new BuildType.SelectorList<>(ImmutableList.of(selector1, selector2), null, currentRule,
          BuildType.LABEL_LIST);
      fail("Expected SelectorList initialization to fail on mixed element types");
    } catch (ConversionException e) {
      assertThat(e.getMessage()).contains("expected value of type 'list(label)'");
    }
  }

  /**
   * Tests that {@link BuildType#selectableConvert} returns either the native type or a selector
   * on that type, in accordance with the provided input.
   */
  @SuppressWarnings("unchecked")
  @Test
  public void testSelectableConvert() throws Exception {
    Object nativeInput = Arrays.asList("//a:a1", "//a:a2");
    Object selectableInput =
        SelectorList.of(new SelectorValue(ImmutableMap.of(
            "//conditions:a", nativeInput,
            BuildType.Selector.DEFAULT_CONDITION_KEY, nativeInput)));
    List<Label> expectedLabels = ImmutableList.of(Label.create("a", "a1"), Label.create("a", "a2"));

    // Conversion to direct type:
    Object converted = BuildType
        .selectableConvert(BuildType.LABEL_LIST, nativeInput, null, currentRule);
    assertTrue(converted instanceof List<?>);
    assertSameContents(expectedLabels, (List<Label>) converted);

    // Conversion to selectable type:
    converted = BuildType
        .selectableConvert(BuildType.LABEL_LIST, selectableInput, null, currentRule);
    BuildType.SelectorList<?> selectorList = (BuildType.SelectorList<?>) converted;
    assertSameContents(
        ImmutableMap.of(
            Label.parseAbsolute("//conditions:a"), expectedLabels,
            Label.parseAbsolute(BuildType.Selector.DEFAULT_CONDITION_KEY),
            expectedLabels).entrySet(),
        ((Selector<Label>) selectorList.getSelectors().get(0)).getEntries().entrySet());
  }

  /**
   * Tests that {@link com.google.devtools.build.lib.syntax.Type#convert} fails on selector inputs.
   */
  @Test
  public void testConvertDoesNotAcceptSelectables() throws Exception {
    Object selectableInput = SelectorList.of(
        new SelectorValue(ImmutableMap.of("//conditions:a", Arrays.asList("//a:a1", "//a:a2"))));
    try {
      BuildType.LABEL_LIST.convert(selectableInput, null, currentRule);
      fail("Expected conversion to fail on a selectable input");
    } catch (ConversionException e) {
      assertThat(e.getMessage()).contains("expected value of type 'list(label)'");
    }
  }

  /**
   * Tests for "reserved" key labels (i.e. not intended to map to actual targets).
   */
  @Test
  public void testReservedKeyLabels() throws Exception {
    assertFalse(BuildType.Selector.isReservedLabel(Label.parseAbsolute("//condition:a")));
    assertTrue(BuildType.Selector.isReservedLabel(
        Label.parseAbsolute(BuildType.Selector.DEFAULT_CONDITION_KEY)));
  }

  private static FilesetEntry makeFilesetEntry() {
    try {
      return new FilesetEntry(Label.parseAbsolute("//foo:bar"),
                              Lists.<Label>newArrayList(), Lists.newArrayList("xyz"), "",
                              FilesetEntry.SymlinkBehavior.COPY, ".");
    } catch (LabelSyntaxException e) {
      throw new RuntimeException("Bad label: ", e);
    }
  }

  private String createExpectedFilesetEntryString(
      FilesetEntry.SymlinkBehavior symlinkBehavior, char quotationMark) {
    return String.format(
        "FilesetEntry(srcdir = %1$c//x:x%1$c,"
        + " files = [%1$c//x:x%1$c],"
        + " excludes = [],"
        + " destdir = %1$c%1$c,"
        + " strip_prefix = %1$c.%1$c,"
        + " symlinks = %1$c%2$s%1$c)",
        quotationMark, symlinkBehavior.toString().toLowerCase());
  }

  private String createExpectedFilesetEntryString(char quotationMark) {
    return createExpectedFilesetEntryString(FilesetEntry.SymlinkBehavior.COPY, quotationMark);
  }

  private FilesetEntry createTestFilesetEntry(
      FilesetEntry.SymlinkBehavior symlinkBehavior)
      throws LabelSyntaxException {
    Label label = Label.parseAbsolute("//x");
    return new FilesetEntry(
        label, Arrays.asList(label), Arrays.<String>asList(), "", symlinkBehavior, ".");
  }

  private FilesetEntry createTestFilesetEntry() throws LabelSyntaxException {
    return createTestFilesetEntry(FilesetEntry.SymlinkBehavior.COPY);
  }

  @Test
  public void testRegressionCrashInPrettyPrintValue() throws Exception {
    // Would cause crash in code such as this:
    //  Fileset(name='x', entries=[], out=[FilesetEntry(files=['a'])])
    // While formatting the "expected x, got y" message for the 'out'
    // attribute, prettyPrintValue(FilesetEntry) would be recursively called
    // with a List<Label> even though this isn't a valid datatype in the
    // interpreter.
    // Fileset isn't part of bazel, even though FilesetEntry is.
    assertEquals(createExpectedFilesetEntryString('"'), Printer.repr(createTestFilesetEntry()));
  }

  @Test
  public void testSingleQuotes() throws Exception {
    assertThat(Printer.repr(createTestFilesetEntry(), '\''))
        .isEqualTo(createExpectedFilesetEntryString('\''));
  }

  @Test
  public void testFilesetEntrySymlinkAttr() throws Exception {
    FilesetEntry entryDereference =
      createTestFilesetEntry(FilesetEntry.SymlinkBehavior.DEREFERENCE);

    assertEquals(
        createExpectedFilesetEntryString(FilesetEntry.SymlinkBehavior.DEREFERENCE, '"'),
        Printer.repr(entryDereference));
  }

  private FilesetEntry createStripPrefixFilesetEntry(String stripPrefix)  throws Exception {
    Label label = Label.parseAbsolute("//x");
    return new FilesetEntry(
        label,
        Arrays.asList(label),
        Arrays.<String>asList(),
        "",
        FilesetEntry.SymlinkBehavior.DEREFERENCE,
        stripPrefix);
  }

  @Test
  public void testFilesetEntryStripPrefixAttr() throws Exception {
    FilesetEntry withoutStripPrefix = createStripPrefixFilesetEntry(".");
    FilesetEntry withStripPrefix = createStripPrefixFilesetEntry("orange");

    String prettyWithout = Printer.repr(withoutStripPrefix);
    String prettyWith = Printer.repr(withStripPrefix);

    assertThat(prettyWithout).contains("strip_prefix = \".\"");
    assertThat(prettyWith).contains("strip_prefix = \"orange\"");
  }

  @Test
  public void testPrintFilesetEntry() throws Exception {
    assertEquals("FilesetEntry(srcdir = \"//foo:bar\", files = [], "
               + "excludes = [\"xyz\"], destdir = \"\", "
               + "strip_prefix = \".\", symlinks = \"copy\")",
                 Printer.repr(makeFilesetEntry()));
  }

  @Test
  public void testFilesetTypeDefinition() throws Exception {
    assertEquals("FilesetEntry",  EvalUtils.getDataTypeName(makeFilesetEntry()));
    assertFalse(EvalUtils.isImmutable(makeFilesetEntry()));
  }
}
