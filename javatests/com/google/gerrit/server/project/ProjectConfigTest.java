// Copyright (C) 2011 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server.project;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.google.common.collect.Iterables;
import com.google.gerrit.common.data.AccessSection;
import com.google.gerrit.common.data.ContributorAgreement;
import com.google.gerrit.common.data.GroupReference;
import com.google.gerrit.common.data.LabelType;
import com.google.gerrit.common.data.Permission;
import com.google.gerrit.common.data.PermissionRule;
import com.google.gerrit.reviewdb.client.AccountGroup;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.client.RefNames;
import com.google.gerrit.server.config.PluginConfig;
import com.google.gerrit.server.extensions.events.GitReferenceUpdated;
import com.google.gerrit.server.git.ValidationError;
import com.google.gerrit.server.git.meta.MetaDataUpdate;
import com.google.gerrit.server.project.testing.Util;
import com.google.gerrit.testing.GerritBaseTests;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.junit.TestRepository;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.RefUpdate.Result;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.util.RawParseUtils;
import org.junit.Before;
import org.junit.Test;

public class ProjectConfigTest extends GerritBaseTests {
  private static final String LABEL_SCORES_CONFIG =
      "  copyMinScore = "
          + !LabelType.DEF_COPY_MIN_SCORE
          + "\n"
          + "  copyMaxScore = "
          + !LabelType.DEF_COPY_MAX_SCORE
          + "\n"
          + "  copyAllScoresOnMergeFirstParentUpdate = "
          + !LabelType.DEF_COPY_ALL_SCORES_ON_MERGE_FIRST_PARENT_UPDATE
          + "\n"
          + "  copyAllScoresOnTrivialRebase = "
          + !LabelType.DEF_COPY_ALL_SCORES_ON_TRIVIAL_REBASE
          + "\n"
          + "  copyAllScoresIfNoCodeChange = "
          + !LabelType.DEF_COPY_ALL_SCORES_IF_NO_CODE_CHANGE
          + "\n"
          + "  copyAllScoresIfNoChange = "
          + !LabelType.DEF_COPY_ALL_SCORES_IF_NO_CHANGE
          + "\n";

  private final GroupReference developers =
      new GroupReference(new AccountGroup.UUID("X"), "Developers");
  private final GroupReference staff = new GroupReference(new AccountGroup.UUID("Y"), "Staff");

  private Repository db;
  private TestRepository<?> tr;

  @Before
  public void setUp() throws Exception {
    db = new InMemoryRepository(new DfsRepositoryDescription("repo"));
    tr = new TestRepository<>(db);
  }

  @Test
  public void readConfig() throws Exception {
    RevCommit rev =
        tr.commit()
            .add("groups", group(developers))
            .add(
                "project.config",
                "[access \"refs/heads/*\"]\n"
                    + "  exclusiveGroupPermissions = read submit create\n"
                    + "  submit = group Developers\n"
                    + "  push = group Developers\n"
                    + "  read = group Developers\n"
                    + "[accounts]\n"
                    + "  sameGroupVisibility = deny group Developers\n"
                    + "  sameGroupVisibility = block group Staff\n"
                    + "[contributor-agreement \"Individual\"]\n"
                    + "  description = A simple description\n"
                    + "  accepted = group Developers\n"
                    + "  accepted = group Staff\n"
                    + "  autoVerify = group Developers\n"
                    + "  agreementUrl = http://www.example.com/agree\n")
            .create();

    ProjectConfig cfg = read(rev);
    assertThat(cfg.getAccountsSection().getSameGroupVisibility()).hasSize(2);
    ContributorAgreement ca = cfg.getContributorAgreement("Individual");
    assertThat(ca.getName()).isEqualTo("Individual");
    assertThat(ca.getDescription()).isEqualTo("A simple description");
    assertThat(ca.getAgreementUrl()).isEqualTo("http://www.example.com/agree");
    assertThat(ca.getAccepted()).hasSize(2);
    assertThat(ca.getAccepted().get(0).getGroup()).isEqualTo(developers);
    assertThat(ca.getAccepted().get(1).getGroup().getName()).isEqualTo("Staff");
    assertThat(ca.getAutoVerify().getName()).isEqualTo("Developers");

    AccessSection section = cfg.getAccessSection("refs/heads/*");
    assertThat(section).isNotNull();
    assertThat(cfg.getAccessSection("refs/*")).isNull();

    Permission create = section.getPermission(Permission.CREATE);
    Permission submit = section.getPermission(Permission.SUBMIT);
    Permission read = section.getPermission(Permission.READ);
    Permission push = section.getPermission(Permission.PUSH);

    assertThat(create.getExclusiveGroup()).isTrue();
    assertThat(submit.getExclusiveGroup()).isTrue();
    assertThat(read.getExclusiveGroup()).isTrue();
    assertThat(push.getExclusiveGroup()).isFalse();
  }

  @Test
  public void readConfigLabelDefaultValue() throws Exception {
    RevCommit rev =
        tr.commit()
            .add("groups", group(developers))
            .add(
                "project.config",
                "[label \"CustomLabel\"]\n"
                    + "  value = -1 Negative\n"
                    // No leading space before 0.
                    + "  value = 0 No Score\n"
                    + "  value =  1 Positive\n")
            .create();

    ProjectConfig cfg = read(rev);
    Map<String, LabelType> labels = cfg.getLabelSections();
    Short dv = labels.entrySet().iterator().next().getValue().getDefaultValue();
    assertThat((int) dv).isEqualTo(0);
  }

  @Test
  public void readConfigLabelOldStyleWithLeadingSpace() throws Exception {
    RevCommit rev =
        tr.commit()
            .add("groups", group(developers))
            .add(
                "project.config",
                "[label \"CustomLabel\"]\n"
                    + "  value = -1 Negative\n"
                    // Leading space before 0.
                    + "  value =  0 No Score\n"
                    + "  value =  1 Positive\n")
            .create();

    ProjectConfig cfg = read(rev);
    Map<String, LabelType> labels = cfg.getLabelSections();
    Short dv = labels.entrySet().iterator().next().getValue().getDefaultValue();
    assertThat((int) dv).isEqualTo(0);
  }

  @Test
  public void readConfigLabelDefaultValueInRange() throws Exception {
    RevCommit rev =
        tr.commit()
            .add("groups", group(developers))
            .add(
                "project.config",
                "[label \"CustomLabel\"]\n"
                    + "  value = -1 Negative\n"
                    + "  value = 0 No Score\n"
                    + "  value =  1 Positive\n"
                    + "  defaultValue = -1\n")
            .create();

    ProjectConfig cfg = read(rev);
    Map<String, LabelType> labels = cfg.getLabelSections();
    Short dv = labels.entrySet().iterator().next().getValue().getDefaultValue();
    assertThat((int) dv).isEqualTo(-1);
  }

  @Test
  public void readConfigLabelDefaultValueNotInRange() throws Exception {
    RevCommit rev =
        tr.commit()
            .add("groups", group(developers))
            .add(
                "project.config",
                "[label \"CustomLabel\"]\n"
                    + "  value = -1 Negative\n"
                    + "  value = 0 No Score\n"
                    + "  value =  1 Positive\n"
                    + "  defaultValue = -2\n")
            .create();

    ProjectConfig cfg = read(rev);
    assertThat(cfg.getValidationErrors()).hasSize(1);
    assertThat(Iterables.getOnlyElement(cfg.getValidationErrors()).getMessage())
        .isEqualTo("project.config: Invalid defaultValue \"-2\" for label \"CustomLabel\"");
  }

  @Test
  public void readConfigLabelScores() throws Exception {
    RevCommit rev =
        tr.commit()
            .add("groups", group(developers))
            .add("project.config", "[label \"CustomLabel\"]\n" + LABEL_SCORES_CONFIG)
            .create();

    ProjectConfig cfg = read(rev);
    Map<String, LabelType> labels = cfg.getLabelSections();
    LabelType type = labels.entrySet().iterator().next().getValue();
    assertThat(type.isCopyMinScore()).isNotEqualTo(LabelType.DEF_COPY_MIN_SCORE);
    assertThat(type.isCopyMaxScore()).isNotEqualTo(LabelType.DEF_COPY_MAX_SCORE);
    assertThat(type.isCopyAllScoresOnMergeFirstParentUpdate())
        .isNotEqualTo(LabelType.DEF_COPY_ALL_SCORES_ON_MERGE_FIRST_PARENT_UPDATE);
    assertThat(type.isCopyAllScoresOnTrivialRebase())
        .isNotEqualTo(LabelType.DEF_COPY_ALL_SCORES_ON_TRIVIAL_REBASE);
    assertThat(type.isCopyAllScoresIfNoCodeChange())
        .isNotEqualTo(LabelType.DEF_COPY_ALL_SCORES_IF_NO_CODE_CHANGE);
    assertThat(type.isCopyAllScoresIfNoChange())
        .isNotEqualTo(LabelType.DEF_COPY_ALL_SCORES_IF_NO_CHANGE);
  }

  @Test
  public void editConfig() throws Exception {
    RevCommit rev =
        tr.commit()
            .add("groups", group(developers))
            .add(
                "project.config",
                "[access \"refs/heads/*\"]\n"
                    + "  exclusiveGroupPermissions = read submit\n"
                    + "  submit = group Developers\n"
                    + "  upload = group Developers\n"
                    + "  read = group Developers\n"
                    + "[accounts]\n"
                    + "  sameGroupVisibility = deny group Developers\n"
                    + "  sameGroupVisibility = block group Staff\n"
                    + "[contributor-agreement \"Individual\"]\n"
                    + "  description = A simple description\n"
                    + "  accepted = group Developers\n"
                    + "  autoVerify = group Developers\n"
                    + "  agreementUrl = http://www.example.com/agree\n"
                    + "[label \"CustomLabel\"]\n"
                    + LABEL_SCORES_CONFIG)
            .create();
    update(rev);

    ProjectConfig cfg = read(rev);
    AccessSection section = cfg.getAccessSection("refs/heads/*");
    cfg.getAccountsSection()
        .setSameGroupVisibility(Collections.singletonList(new PermissionRule(cfg.resolve(staff))));
    Permission submit = section.getPermission(Permission.SUBMIT);
    submit.add(new PermissionRule(cfg.resolve(staff)));
    ContributorAgreement ca = cfg.getContributorAgreement("Individual");
    ca.setAccepted(Collections.singletonList(new PermissionRule(cfg.resolve(staff))));
    ca.setAutoVerify(null);
    ca.setDescription("A new description");
    rev = commit(cfg);
    assertThat(text(rev, "project.config"))
        .isEqualTo(
            "[access \"refs/heads/*\"]\n"
                + "  exclusiveGroupPermissions = read submit\n"
                + "  submit = group Developers\n"
                + "\tsubmit = group Staff\n"
                + "  upload = group Developers\n"
                + "  read = group Developers\n"
                + "[accounts]\n"
                + "  sameGroupVisibility = group Staff\n"
                + "[contributor-agreement \"Individual\"]\n"
                + "  description = A new description\n"
                + "  accepted = group Staff\n"
                + "  agreementUrl = http://www.example.com/agree\n"
                + "[label \"CustomLabel\"]\n"
                + LABEL_SCORES_CONFIG
                + "\tfunction = MaxWithBlock\n" // label gets this function when it is created
                + "\tdefaultValue = 0\n"); //  label gets this value when it is created
  }

  @Test
  public void editConfigLabelValues() throws Exception {
    RevCommit rev = tr.commit().create();
    update(rev);

    ProjectConfig cfg = read(rev);
    cfg.getLabelSections()
        .put(
            "My-Label",
            Util.category(
                "My-Label",
                Util.value(-1, "Negative"),
                Util.value(0, "No score"),
                Util.value(1, "Positive")));
    rev = commit(cfg);
    assertThat(text(rev, "project.config"))
        .isEqualTo(
            "[label \"My-Label\"]\n"
                + "\tfunction = MaxWithBlock\n"
                + "\tdefaultValue = 0\n"
                + "\tvalue = -1 Negative\n"
                + "\tvalue = 0 No score\n"
                + "\tvalue = +1 Positive\n");
  }

  @Test
  public void addCommentLink() throws Exception {
    RevCommit rev = tr.commit().create();
    update(rev);

    ProjectConfig cfg = read(rev);
    CommentLinkInfoImpl cm = new CommentLinkInfoImpl("Test", "abc.*", null, "<a>link</a>", true);
    cfg.addCommentLinkSection(cm);
    rev = commit(cfg);
    assertThat(text(rev, "project.config"))
        .isEqualTo("[commentlink \"Test\"]\n\tmatch = abc.*\n\thtml = <a>link</a>\n");
  }

  @Test
  public void editConfigMissingGroupTableEntry() throws Exception {
    RevCommit rev =
        tr.commit()
            .add("groups", group(developers))
            .add(
                "project.config",
                "[access \"refs/heads/*\"]\n"
                    + "  exclusiveGroupPermissions = read submit\n"
                    + "  submit = group People Who Can Submit\n"
                    + "  upload = group Developers\n"
                    + "  read = group Developers\n")
            .create();
    update(rev);

    ProjectConfig cfg = read(rev);
    AccessSection section = cfg.getAccessSection("refs/heads/*");
    Permission submit = section.getPermission(Permission.SUBMIT);
    submit.add(new PermissionRule(cfg.resolve(staff)));
    rev = commit(cfg);
    assertThat(text(rev, "project.config"))
        .isEqualTo(
            "[access \"refs/heads/*\"]\n"
                + "  exclusiveGroupPermissions = read submit\n"
                + "  submit = group People Who Can Submit\n"
                + "\tsubmit = group Staff\n"
                + "  upload = group Developers\n"
                + "  read = group Developers\n");
  }

  @Test
  public void readExistingPluginConfig() throws Exception {
    RevCommit rev =
        tr.commit()
            .add(
                "project.config",
                "[plugin \"somePlugin\"]\n"
                    + "  key1 = value1\n"
                    + "  key2 = value2a\n"
                    + "  key2 = value2b\n")
            .create();
    update(rev);

    ProjectConfig cfg = read(rev);
    PluginConfig pluginCfg = cfg.getPluginConfig("somePlugin");
    assertThat(pluginCfg.getNames()).hasSize(2);
    assertThat(pluginCfg.getString("key1")).isEqualTo("value1");
    assertThat(pluginCfg.getStringList(("key2"))).isEqualTo(new String[] {"value2a", "value2b"});
  }

  @Test
  public void readUnexistingPluginConfig() throws Exception {
    ProjectConfig cfg = new ProjectConfig(new Project.NameKey("test"));
    cfg.load(db);
    PluginConfig pluginCfg = cfg.getPluginConfig("somePlugin");
    assertThat(pluginCfg.getNames()).isEmpty();
  }

  @Test
  public void editPluginConfig() throws Exception {
    RevCommit rev =
        tr.commit()
            .add(
                "project.config",
                "[plugin \"somePlugin\"]\n"
                    + "  key1 = value1\n"
                    + "  key2 = value2a\n"
                    + "  key2 = value2b\n")
            .create();
    update(rev);

    ProjectConfig cfg = read(rev);
    PluginConfig pluginCfg = cfg.getPluginConfig("somePlugin");
    pluginCfg.setString("key1", "updatedValue1");
    pluginCfg.setStringList("key2", Arrays.asList("updatedValue2a", "updatedValue2b"));
    rev = commit(cfg);
    assertThat(text(rev, "project.config"))
        .isEqualTo(
            "[plugin \"somePlugin\"]\n"
                + "\tkey1 = updatedValue1\n"
                + "\tkey2 = updatedValue2a\n"
                + "\tkey2 = updatedValue2b\n");
  }

  @Test
  public void readPluginConfigGroupReference() throws Exception {
    RevCommit rev =
        tr.commit()
            .add("groups", group(developers))
            .add("project.config", "[plugin \"somePlugin\"]\nkey1 = " + developers.toConfigValue())
            .create();
    update(rev);

    ProjectConfig cfg = read(rev);
    PluginConfig pluginCfg = cfg.getPluginConfig("somePlugin");
    assertThat(pluginCfg.getNames()).hasSize(1);
    assertThat(pluginCfg.getGroupReference("key1")).isEqualTo(developers);
  }

  @Test
  public void readPluginConfigGroupReferenceNotInGroupsFile() throws Exception {
    RevCommit rev =
        tr.commit()
            .add("groups", group(developers))
            .add("project.config", "[plugin \"somePlugin\"]\nkey1 = " + staff.toConfigValue())
            .create();
    update(rev);

    ProjectConfig cfg = read(rev);
    assertThat(cfg.getValidationErrors()).hasSize(1);
    assertThat(Iterables.getOnlyElement(cfg.getValidationErrors()).getMessage())
        .isEqualTo(
            "project.config: group \"" + staff.getName() + "\" not in " + GroupList.FILE_NAME);
  }

  @Test
  public void editPluginConfigGroupReference() throws Exception {
    RevCommit rev =
        tr.commit()
            .add("groups", group(developers))
            .add("project.config", "[plugin \"somePlugin\"]\nkey1 = " + developers.toConfigValue())
            .create();
    update(rev);

    ProjectConfig cfg = read(rev);
    PluginConfig pluginCfg = cfg.getPluginConfig("somePlugin");
    assertThat(pluginCfg.getNames()).hasSize(1);
    assertThat(pluginCfg.getGroupReference("key1")).isEqualTo(developers);

    pluginCfg.setGroupReference("key1", staff);
    rev = commit(cfg);
    assertThat(text(rev, "project.config"))
        .isEqualTo("[plugin \"somePlugin\"]\n\tkey1 = " + staff.toConfigValue() + "\n");
    assertThat(text(rev, "groups"))
        .isEqualTo(
            "# UUID\tGroup Name\n"
                + "#\n"
                + staff.getUUID().get()
                + "     \t"
                + staff.getName()
                + "\n");
  }

  @Test
  public void readCommentLinks() throws Exception {
    RevCommit rev =
        tr.commit()
            .add(
                "project.config",
                "[commentlink \"bugzilla\"]\n"
                    + "\tmatch = \"(bug\\\\s+#?)(\\\\d+)\"\n"
                    + "\tlink = http://bugs.example.com/show_bug.cgi?id=$2")
            .create();
    ProjectConfig cfg = read(rev);
    assertThat(cfg.getCommentLinkSections())
        .containsExactly(
            new CommentLinkInfoImpl(
                "bugzilla",
                "(bug\\s+#?)(\\d+)",
                "http://bugs.example.com/show_bug.cgi?id=$2",
                null,
                null));
  }

  @Test
  public void readCommentLinksNoHtmlOrLinkButEnabled() throws Exception {
    RevCommit rev =
        tr.commit().add("project.config", "[commentlink \"bugzilla\"]\n \tenabled = true").create();
    ProjectConfig cfg = read(rev);
    assertThat(cfg.getCommentLinkSections())
        .containsExactly(new CommentLinkInfoImpl.Enabled("bugzilla"));
  }

  @Test
  public void readCommentLinksNoHtmlOrLinkAndDisabled() throws Exception {
    RevCommit rev =
        tr.commit()
            .add("project.config", "[commentlink \"bugzilla\"]\n \tenabled = false")
            .create();
    ProjectConfig cfg = read(rev);
    assertThat(cfg.getCommentLinkSections())
        .containsExactly(new CommentLinkInfoImpl.Disabled("bugzilla"));
  }

  @Test
  public void readCommentLinkInvalidPattern() throws Exception {
    RevCommit rev =
        tr.commit()
            .add(
                "project.config",
                "[commentlink \"bugzilla\"]\n"
                    + "\tmatch = \"(bugs{+#?)(d+)\"\n"
                    + "\tlink = http://bugs.example.com/show_bug.cgi?id=$2")
            .create();
    ProjectConfig cfg = read(rev);
    assertThat(cfg.getCommentLinkSections()).isEmpty();
    assertThat(cfg.getValidationErrors())
        .containsExactly(
            new ValidationError(
                "project.config: Invalid pattern \"(bugs{+#?)(d+)\" in commentlink.bugzilla.match: "
                    + "Illegal repetition near index 4\n"
                    + "(bugs{+#?)(d+)\n"
                    + "    ^"));
  }

  @Test
  public void readCommentLinkRawHtml() throws Exception {
    RevCommit rev =
        tr.commit()
            .add(
                "project.config",
                "[commentlink \"bugzilla\"]\n"
                    + "\tmatch = \"(bugs#?)(d+)\"\n"
                    + "\thtml = http://bugs.example.com/show_bug.cgi?id=$2")
            .create();
    ProjectConfig cfg = read(rev);
    assertThat(cfg.getCommentLinkSections()).isEmpty();
    assertThat(cfg.getValidationErrors())
        .containsExactly(
            new ValidationError(
                "project.config: Error in pattern \"(bugs#?)(d+)\" in commentlink.bugzilla.match: "
                    + "Raw html replacement not allowed"));
  }

  @Test
  public void readCommentLinkMatchButNoHtmlOrLink() throws Exception {
    RevCommit rev =
        tr.commit()
            .add("project.config", "[commentlink \"bugzilla\"]\n" + "\tmatch = \"(bugs#?)(d+)\"\n")
            .create();
    ProjectConfig cfg = read(rev);
    assertThat(cfg.getCommentLinkSections()).isEmpty();
    assertThat(cfg.getValidationErrors())
        .containsExactly(
            new ValidationError(
                "project.config: Error in pattern \"(bugs#?)(d+)\" in commentlink.bugzilla.match: "
                    + "commentlink.bugzilla must have either link or html"));
  }

  private ProjectConfig read(RevCommit rev) throws IOException, ConfigInvalidException {
    ProjectConfig cfg = new ProjectConfig(new Project.NameKey("test"));
    cfg.load(db, rev);
    return cfg;
  }

  private RevCommit commit(ProjectConfig cfg)
      throws IOException, MissingObjectException, IncorrectObjectTypeException {
    try (MetaDataUpdate md =
        new MetaDataUpdate(GitReferenceUpdated.DISABLED, cfg.getProject().getNameKey(), db)) {
      tr.tick(5);
      tr.setAuthorAndCommitter(md.getCommitBuilder());
      md.setMessage("Edit\n");
      cfg.commit(md);

      Ref ref = db.exactRef(RefNames.REFS_CONFIG);
      return tr.getRevWalk().parseCommit(ref.getObjectId());
    }
  }

  private void update(RevCommit rev) throws Exception {
    RefUpdate u = db.updateRef(RefNames.REFS_CONFIG);
    u.disableRefLog();
    u.setNewObjectId(rev);
    Result result = u.forceUpdate();
    assertWithMessage("Cannot update ref for test: " + result)
        .that(result)
        .isAnyOf(Result.FAST_FORWARD, Result.FORCED, Result.NEW, Result.NO_CHANGE);
  }

  private String text(RevCommit rev, String path) throws Exception {
    RevObject blob = tr.get(rev.getTree(), path);
    byte[] data = db.open(blob).getCachedBytes(Integer.MAX_VALUE);
    return RawParseUtils.decode(data);
  }

  private static String group(GroupReference g) {
    return g.getUUID().get() + "\t" + g.getName() + "\n";
  }
}
