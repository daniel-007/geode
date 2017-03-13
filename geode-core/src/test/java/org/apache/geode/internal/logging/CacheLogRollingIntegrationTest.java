/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.geode.internal.logging;

import static org.apache.geode.distributed.ConfigurationProperties.*;
import static org.assertj.core.api.Assertions.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.Properties;
import java.util.regex.Pattern;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.TestName;

import org.apache.geode.LogWriter;
import org.apache.geode.distributed.DistributedSystem;
import org.apache.geode.test.junit.categories.IntegrationTest;

/**
 * Integration tests for log rolling with cache lifecycle.
 *
 * @since GemFire 6.5
 */
@Category(IntegrationTest.class)
public class CacheLogRollingIntegrationTest {

  private static final int MAX_LOG_STATEMENTS = 100000;
  private static final String SECURITY_PREFIX = "security_";

  private String baseName;
  private File dir;
  private File logFile;
  private File securityLogFile;
  private Pattern mainIdPattern;
  private DistributedSystem system;

  @Rule
  public TemporaryFolder temporaryFolder = new TemporaryFolder();

  @Rule
  public TestName testName = new TestName();

  @Before
  public void before() throws Exception {
    this.baseName = this.testName.getMethodName();
    this.dir = this.temporaryFolder.getRoot();
    this.logFile = new File(this.dir, logFileName());
    this.securityLogFile = new File(this.dir, securityLogFileName());
    this.mainIdPattern = Pattern.compile("meta-" + this.baseName + "-\\d\\d.log");
  }

  @After
  public void after() throws Exception {
    if (this.system != null) {
      this.system.disconnect();
    }
  }

  @Test
  public void testSimpleStartRestartWithRolling() throws Exception {
    Properties config = new Properties();
    config.put(LOG_FILE, this.logFile.getAbsolutePath());
    config.put(LOG_FILE_SIZE_LIMIT, "1");
    config.put(LOG_DISK_SPACE_LIMIT, "200");

    this.system = DistributedSystem.connect(config);
    this.system.disconnect();

    for (int mainInt = 2; mainInt <= 4; mainInt++) {
      assertThat(metaFile(mainInt - 1)).exists();

      File newMetaFile = metaFile(mainInt);
      File newRolledLogFile = childFile(mainInt - 1, 1);

      assertThat(newMetaFile).doesNotExist();
      assertThat(newRolledLogFile).doesNotExist();

      this.system = DistributedSystem.connect(config);

      assertThat(newMetaFile).exists();
      assertThat(newRolledLogFile).exists();

      this.system.disconnect();
    }
  }

  @Test
  public void testStartWithRollingThenRestartWithRolling() throws Exception {
    Properties config = new Properties();
    config.put(LOG_FILE, this.logFile.getAbsolutePath());
    config.put(LOG_FILE_SIZE_LIMIT, "1");

    this.system = DistributedSystem.connect(config);

    logAndRollAndVerify(1);

    DistributedSystem firstSystem = this.system;

    assertThat(this.logFile).exists();
    assertThat(childFile(1, 1)).exists();
    assertThat(childFile(1, 2)).exists();
    assertThat(childFile(1, 3)).doesNotExist();
    assertThat(childFile(2, 1)).doesNotExist();

    this.system.disconnect();

    config.put(LOG_DISK_SPACE_LIMIT, "200");
    this.system = DistributedSystem.connect(config);

    assertThat(this.system).isNotSameAs(firstSystem);
    assertThat(childFile(1, 3)).exists();
  }

  @Test
  public void testLogFileLayoutAndRolling() throws Exception {
    Properties config = new Properties();
    config.put(LOG_FILE, this.logFile.getAbsolutePath());
    config.put(LOG_FILE_SIZE_LIMIT, "1");

    this.system = DistributedSystem.connect(config);

    logAndRollAndVerify(1);
  }

  @Test
  public void testSecurityLogFileLayoutAndRolling() throws Exception {
    Properties config = new Properties();
    config.put(LOG_FILE, this.logFile.getAbsolutePath());
    config.put(LOG_FILE_SIZE_LIMIT, "1");
    config.put(SECURITY_LOG_FILE, this.securityLogFile.getAbsolutePath());

    this.system = DistributedSystem.connect(config);

    securityLogAndRollAndVerify(1);
  }

  @Test
  public void with_logFileSizeLimit_should_createMetaLogFile() throws Exception {
    Properties config = new Properties();
    config.put(LOG_FILE, this.logFile.getAbsolutePath());
    config.put(LOG_FILE_SIZE_LIMIT, "1");

    this.system = DistributedSystem.connect(config);

    File[] metaLogsMatched =
        this.dir.listFiles((dir, name) -> mainIdPattern.matcher(name).matches());
    assertThat(metaLogsMatched).hasSize(1);

    File metaLogFile = metaFile(1);
    assertThat(metaLogFile).exists();
  }

  @Test
  public void without_logFileSizeLimit_shouldNot_createMetaLogFile() throws Exception {
    Properties config = new Properties();
    config.put(LOG_FILE, this.logFile.getAbsolutePath());

    this.system = DistributedSystem.connect(config);

    File[] metaLogsMatched =
        this.dir.listFiles((dir, name) -> mainIdPattern.matcher(name).matches());
    assertThat(metaLogsMatched).hasSize(0);

    File metaLogFile = metaFile(12);
    assertThat(metaLogFile).doesNotExist();
  }

  private String readContents(final File file) throws IOException {
    assertThat(file).exists();

    BufferedReader reader = new BufferedReader(new FileReader(file));
    StringBuffer buffer = new StringBuffer();
    int numRead;
    char[] chars = new char[1024];

    while ((numRead = reader.read(chars)) != -1) {
      String readData = String.valueOf(chars, 0, numRead);
      buffer.append(readData);
      chars = new char[1024];
    }

    return buffer.toString();
  }

  /**
   * 1. Lets assert that the logfile exists and that it is a proper normal logfile<br>
   * 2. Assert that the meta logfile exists and has good stuff in it<br>
   * 3. Let's log a bunch and show that we rolled<br>
   * 4. Show that old file has right old stuff in it<br>
   * 5. Show that new file has right new stuff in it<br>
   * 6. Show that meta has right stuff in it<br>
   */
  private void logAndRollAndVerify(final int mainId) throws IOException {
    File metaLogFile = metaFile(mainId);
    File childLogFile01 = childFile(mainId, 1);
    File childLogFile02 = childFile(mainId, 2);

    String switchingToLog = "Switching to log " + this.logFile;
    String rollingCurrentLogTo01 = "Rolling current log to " + childLogFile01;
    String rollingCurrentLogTo02 = "Rolling current log to " + childLogFile02;

    String messageInChild = "hey guys im the first child";
    String message = "hey whatsup i can't believe it wow";

    this.system.getLogWriter().info(messageInChild);

    assertThat(this.logFile).exists();
    assertThat(metaLogFile).exists();
    assertThat(childLogFile01).doesNotExist();
    assertThat(childLogFile02).doesNotExist();
    assertThat(readContents(metaLogFile)).contains(switchingToLog);
    assertThat(readContents(this.logFile)).contains(messageInChild);

    logUntilFileExists(this.system.getLogWriter(), message, childLogFile02);

    assertThat(childLogFile01).exists();
    assertThat(childLogFile02).exists();

    String metaLogContents = readContents(metaLogFile);
    assertThat(metaLogContents).contains(rollingCurrentLogTo01);
    assertThat(metaLogContents).contains(rollingCurrentLogTo02);
    assertThat(metaLogContents).doesNotContain(message);

    assertThat(readContents(this.logFile)).contains(message);
    assertThat(readContents(childLogFile01)).contains(message);
    assertThat(readContents(childLogFile02)).contains(message);
  }

  /**
   * 1. Lets assert that the logfile exists and that it is a proper normal logfile<br>
   * 2. Assert that the meta logfile exists and has good stuff in it<br>
   * 3. Let's log a bunch and show that we rolled<br>
   * 4. Show that old file has right old stuff in it<br>
   * 5. Show that new file has right new stuff in it<br>
   * 6. Show that meta has right stuff in it<br>
   */
  private void securityLogAndRollAndVerify(final int mainId) throws IOException {
    File metaLogFile = metaFile(mainId);
    File childLogFile01 = childFile(mainId, 1);
    File childLogFile02 = childFile(mainId, 2);
    File childSecurityLogFile01 = childSecurityFile(mainId, 1);
    File childSecurityLogFile02 = childSecurityFile(mainId, 2);

    String switchingToLog = "Switching to log " + this.logFile;
    String rollingCurrentLogTo01 = "Rolling current log to " + childLogFile01;
    String rollingCurrentLogTo02 = "Rolling current log to " + childLogFile02;

    String messageInChild = "hey guys im the first child";
    String messageInSecurityChild = "hey guys im the first security child";
    String message = "hey whatsup i can't believe it wow";

    this.system.getLogWriter().info(messageInChild);
    this.system.getSecurityLogWriter().info(messageInSecurityChild);

    assertThat(readContents(this.logFile)).contains(messageInChild)
        .doesNotContain(messageInSecurityChild);
    assertThat(readContents(this.securityLogFile)).contains(messageInSecurityChild)
        .doesNotContain(messageInChild);

    assertThat(readContents(metaLogFile)).contains(switchingToLog);

    assertThat(childLogFile01).doesNotExist();
    assertThat(childSecurityLogFile01).doesNotExist();
    assertThat(childLogFile02).doesNotExist();
    assertThat(childSecurityLogFile02).doesNotExist();

    logUntilFileExists(this.system.getLogWriter(), message, childLogFile02);
    logUntilFileExists(this.system.getSecurityLogWriter(), message, childSecurityLogFile02);

    assertThat(readContents(this.logFile)).contains(message);
    assertThat(readContents(this.securityLogFile)).contains(message);

    String metaLogContents = readContents(metaLogFile);
    assertThat(metaLogContents).contains(rollingCurrentLogTo01);
    assertThat(metaLogContents).contains(rollingCurrentLogTo02);
    assertThat(metaLogContents).doesNotContain(message);

    assertThat(readContents(childLogFile01)).contains(message);
    assertThat(readContents(childSecurityLogFile01)).contains(message);
    assertThat(readContents(childLogFile02)).contains(message);
    assertThat(readContents(childSecurityLogFile02)).contains(message);
  }

  private void logUntilFileExists(final LogWriter logWriter, final String message,
      final File logFile) {
    for (int i = 0; i < MAX_LOG_STATEMENTS && !logFile.exists(); i++) {
      logWriter.info(message);
    }
    assertThat(logFile).exists();
  }

  private String formatId(final int id) {
    return String.format("%02d", id);
  }

  private String logFileName() {
    return this.baseName + ".log";
  }

  private String securityLogFileName() {
    return SECURITY_PREFIX + this.baseName + ".log";
  }

  private String metaFileName(int mainId) {
    return "meta-" + this.baseName + "-" + formatId(mainId) + ".log";
  }

  private File metaFile(int mainId) {
    return new File(this.dir, metaFileName(mainId));
  }

  private String childFileName(int mainId, int childId) {
    return this.baseName + "-" + formatId(mainId) + "-" + formatId(childId) + ".log";
  }

  private File childFile(int mainId, int childId) {
    return new File(this.dir, childFileName(mainId, childId));
  }

  private String childSecurityFileName(int mainId, int childId) {
    return SECURITY_PREFIX + this.baseName + "-" + formatId(mainId) + "-" + formatId(childId)
        + ".log";
  }

  private File childSecurityFile(int mainId, int childId) {
    return new File(this.dir, childSecurityFileName(mainId, childId));
  }

}
