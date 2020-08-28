package app.unattach.model;

import org.junit.Test;

import java.security.InvalidParameterException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

public class FilenameFactoryTest {
  private static final Email email = new Email("id3", "uid42", Arrays.asList("SENT", "IMPORTANT"),
      "\"Rok Strniša\" <rok.strnisa@gmail.com>", "subject", 1501574400000L, 32141);

  @Test
  public void testFromEmail() {
    testGetFilename("${FROM_EMAIL}", "a%b@.jpg", "rok.strnisa@gmail.com");
    testGetFilename("${FROM_EMAIL:3}", "a%b@.jpg", "rok");
  }

  @Test
  public void testFromName() {
    testGetFilename("${FROM_NAME}", "a%b@.jpg", "Rok_Strni_a");
    testGetFilename("${FROM_NAME:3}", "a%b@.jpg", "Rok");
  }

  @Test
  public void testSubject() {
    testGetFilename("${SUBJECT}", "a%b@.jpg", "subject");
    testGetFilename("${SUBJECT:3}", "a%b@.jpg", "sub");
  }

  @Test
  public void testTimestamp() {
    testGetFilename("${TIMESTAMP}", "a%b@.jpg", "1501574400000");
    testGetFilename("${TIMESTAMP:3}", "a%b@.jpg", "150");
  }

  @Test
  public void testDate() {
    testGetFilename("${DATE}", "a%b@.jpg", "2017-08-01");
    testGetFilename("${DATE:7}", "a%b@.jpg", "2017-08");
  }

  @Test
  public void testEmailId() {
    testGetFilename("${ID}", "a%b@.jpg", "id3");
    testGetFilename("${ID:2}", "a%b@.jpg","id");
  }

  @Test
  public void testBodyPartIndex() {
    testGetFilename("${BODY_PART_INDEX}", "a%b@.jpg", "234");
    testGetFilename("${BODY_PART_INDEX:1}", "a%b@.jpg", "2");
  }

  @Test
  public void testAttachmentName() {
    testGetFilename("${RAW_ATTACHMENT_NAME}", "a%b~.jpg", "a%b~.jpg");
    testGetFilename("${RAW_ATTACHMENT_NAME:3}", "a%b~", "a%b");
    testGetFilename("${RAW_ATTACHMENT_NAME:6}", "a%b~.jpg", "a%.jpg");
  }

  @Test
  public void testNormalizedAttachmentName() {
    testGetFilename("${ATTACHMENT_NAME}", "a%b~.jpg", "a_b_.jpg");
    testGetFilename("${ATTACHMENT_NAME:3}", "a%b~", "a_b");
    testGetFilename("${ATTACHMENT_NAME:6}", "a%b~.jpg", "a_.jpg");
  }

  @Test
  public void testLabels() {
    testGetFilename("${LABELS}", "a%b@.jpg", "IMPORTANT_SENT");
  }

  @Test(expected = InvalidParameterException.class)
  public void testUnknownPlaceholder() {
    testGetFilename("${FOO}", "a%b@.jpg", "a%b@.jpg");
  }

  private static void testGetFilename(String schema, String attachmentName, String expectedFilename) {
    FilenameFactory filenameFactory = new FilenameFactory(schema);
    String actualFilename = filenameFactory.getFilename(email, 234, attachmentName);
    assertEquals(expectedFilename, actualFilename);
  }
}