package app.unattach.model;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import javax.mail.BodyPart;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeUtility;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.logging.Logger;

class EmailProcessor {
  private static final Logger LOGGER = Logger.getLogger(EmailProcessor.class.getName());

  private final Email email;
  private final ProcessSettings processSettings;
  private final FilenameFactory filenameFactory;
  private int fileCounter = 0;
  private final List<BodyPart> copiedBodyParts;
  private final Map<String, String> originalToNormalizedFilename;
  private BodyPart mainTextBodyPart;
  private BodyPart mainHtmlBodyPart;

  private EmailProcessor(Email email, ProcessSettings processSettings) {
    this.email = email;
    this.processSettings = processSettings;
    filenameFactory = new FilenameFactory(processSettings.filenameSchema);
    copiedBodyParts = new LinkedList<>();
    originalToNormalizedFilename = new TreeMap<>();
  }

  static Set<String> process(Email email, MimeMessage mimeMessage, ProcessSettings processSettings)
      throws IOException, MessagingException {
    EmailProcessor processor = new EmailProcessor(email, processSettings);
    processor.exploreContent(mimeMessage.getContent());
    processor.removeCopiedBodyParts();
    if (processSettings.addMetadata) {
      processor.addReferencesToContent();
    }
    mimeMessage.saveChanges();
    return processor.originalToNormalizedFilename.keySet();
  }

  private void exploreContent(Object content) throws MessagingException, IOException {
    if (content instanceof Multipart) {
      Multipart multipart = (Multipart) content;
      for (int i = 0; i < multipart.getCount(); ++i) {
        BodyPart bodyPart = multipart.getBodyPart(i);
        handleBodyPart(bodyPart);
        fixInvalidContentType(bodyPart);
        exploreContent(bodyPart.getContent());
      }
    }
  }

  private void fixInvalidContentType(BodyPart bodyPart) throws MessagingException {
    String[] contentTypes = bodyPart.getHeader("Content-Type");
    if (contentTypes == null) {
      LOGGER.warning("No Content-Type header found.");
      return;
    }
    if (contentTypes.length == 1) {
      String contentType = contentTypes[0];
      if (contentType.contains("iso-8859-8-i")) {
        String newContentType = contentType.replace("iso-8859-8-i", "iso-8859-8");
        bodyPart.setHeader("Content-Type", newContentType);
      }
    }
  }

  private void handleBodyPart(BodyPart bodyPart) throws MessagingException, IOException {
    if (isDownloadableBodyPart(bodyPart)) {
      copyBodyPartToDisk(bodyPart);
    } else {
      if (bodyPart.isMimeType("text/plain") && mainTextBodyPart == null) {
        mainTextBodyPart = bodyPart;
      } else if (bodyPart.isMimeType("text/html") && mainHtmlBodyPart == null) {
        mainHtmlBodyPart = bodyPart;
      }
    }
  }

  private boolean isDownloadableBodyPart(BodyPart bodyPart) throws MessagingException {
    return (bodyPart.getDisposition() != null && !"inline".equals(bodyPart.getDisposition()));
  }

  private void copyBodyPartToDisk(BodyPart bodyPart) throws IOException, MessagingException {
    try (InputStream inputStream = bodyPart.getInputStream()) {
      String originalFilename = getFilename(bodyPart);
      if (originalFilename == null) {
        return;
      }
      String normalizedFilename = filenameFactory.getFilename(email, fileCounter++, originalFilename);
      if (processSettings.processOption.shouldDownload()) {
        Path targetPath = Path.of(processSettings.targetDirectory.getAbsolutePath(), normalizedFilename);
        File targetFile = targetPath.toFile();
        //noinspection ResultOfMethodCallIgnored
        targetFile.getParentFile().mkdirs();
        Files.copy(inputStream, targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        //noinspection ResultOfMethodCallIgnored
        targetFile.setLastModified(email.getTimestamp());
      }
      copiedBodyParts.add(bodyPart);
      originalToNormalizedFilename.put(originalFilename, normalizedFilename);
      LOGGER.info("Saved attachment " + originalFilename + " from " + email + " as " + normalizedFilename + ".");
    }
  }

  private String getFilename(BodyPart bodyPart) throws MessagingException, UnsupportedEncodingException {
    String rawFilename = bodyPart.getFileName();
    if (rawFilename == null) {
      return null;
    }
    return MimeUtility.decodeText(rawFilename).trim();
  }

  private void removeCopiedBodyParts() throws MessagingException {
    for (BodyPart bodyPart : copiedBodyParts) {
      bodyPart.getParent().removeBodyPart(bodyPart);
    }
  }

  private void addReferencesToContent() throws IOException, MessagingException {
    if (originalToNormalizedFilename.size() == 0) {
      return;
    }
    String dateTimeString = OffsetDateTime.now().toString();
    String hostname = getHostname();
    if (mainTextBodyPart != null) {
      String text = mainTextBodyPart.getContent().toString();
      String newText = generateTextSuffix(text, originalToNormalizedFilename, dateTimeString, hostname);
      mainTextBodyPart.setContent(newText, "text/plain; charset=utf-8");
    }
    if (mainHtmlBodyPart != null) {
      String html = mainHtmlBodyPart.getContent().toString();
      String newHtml = generateHtmlSuffix(html, originalToNormalizedFilename, dateTimeString, hostname);
      mainHtmlBodyPart.setContent(newHtml, "text/html; charset=utf-8");
    }
  }

  private String getHostname() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      return "(unknown)";
    }
  }

  private String generateTextSuffix(String text, Map<String, String> originalToNormalizedFilename,
                                    String dateTimeString, String hostname) {
    StringBuilder newText = new StringBuilder(text);
    newText.append("\n\n\n");
    newText.append("=========================================\n");
    newText.append("Previous attachments:\n");
    for (Map.Entry<String, String> entry : originalToNormalizedFilename.entrySet()) {
      String originalFilename = entry.getKey();
      String normalizedFilename = entry.getValue();
      newText.append(" - ").append(originalFilename);
      if (processSettings.processOption.shouldDownload()) {
        newText.append(" (filename: ").append(normalizedFilename).append(")");
      }
      newText.append("\n");
    }
    newText.append("\nInformation about the change:\n");
    newText.append(" - Made with:                 ").append(Constants.PRODUCT_NAME + " " +
        Constants.VERSION).append("\n");
    newText.append(" - Date and time:             ").append(dateTimeString).append("\n");
    if (processSettings.processOption.shouldDownload()) {
      newText.append(" - Download target hostname:  ").append(hostname).append("\n");
      newText.append(" - Download target directory: ").append(processSettings.targetDirectory.getAbsolutePath()).append("\n");
    }
    return newText.toString();
  }

  private String generateHtmlSuffix(String html, Map<String, String> originalToNormalizedFilename,
                                    String dateTimeString, String hostname) {
    StringBuilder suffix = new StringBuilder("<hr /><p>Previous attachments:<ul>\n");
    String targetDirectoryAbsolutePath = processSettings.targetDirectory.getAbsolutePath();
    for (Map.Entry<String, String> entry : originalToNormalizedFilename.entrySet()) {
      String originalFilename = entry.getKey();
      String normalizedFilename = entry.getValue();
      suffix.append("<li>");
      suffix.append(originalFilename);
      if (processSettings.processOption.shouldDownload()) {
        suffix.append(" (");
        Path normalisedPath = Paths.get(targetDirectoryAbsolutePath, normalizedFilename);
        suffix.append("filename: ").append(normalisedPath).append(", ");
        suffix.append("<a href='file:///").append(normalisedPath).append("'>local link</a>");
        suffix.append(")");
      }
      suffix.append("</li>\n");
    }
    suffix.append("</ul></p>\n");
    suffix.append("<p>Information about the change:<ul>\n");
    suffix.append("<li>Made with: ").append("<a href='" + Constants.HOMEPAGE + "'>" + Constants.PRODUCT_NAME + "</a> " +
        Constants.VERSION).append("</li>\n");
    suffix.append("<li>Date and time: ").append(dateTimeString).append("</li>\n");
    if (processSettings.processOption.shouldDownload()) {
      suffix.append("<li>Download target host name: ").append(hostname).append("</li>\n");
      suffix.append("<li>Download target directory: ").append(targetDirectoryAbsolutePath).append("</li>\n");
      suffix.append("<li><i>File links only work in native email apps (e.g. Mail, Outlook) on the target host.</i></li>\n");
    }
    suffix.append("</ul></p>\n");
    Document document = Jsoup.parse(html);
    document.body().append(suffix.toString());
    return document.toString();
  }
}
