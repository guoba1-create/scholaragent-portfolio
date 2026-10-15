package com.team.rag.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Optional GROBID client for academic PDF parsing.
 *
 * <p>GROBID returns TEI XML. This parser reads only the TEI body and ignores
 * back matter, so references/bibliography are not indexed.</p>
 */
@Service
public class GrobidDocumentParser {

    private static final Logger log = LoggerFactory.getLogger(GrobidDocumentParser.class);
    private static final String TEI_NS = "http://www.tei-c.org/ns/1.0";

    private final WebClient webClient;
    private final boolean enabled;
    private final String baseUrl;
    private final Duration timeout;

    public GrobidDocumentParser(WebClient.Builder builder,
                                @Value("${team-rag.grobid.enabled:true}") boolean enabled,
                                @Value("${team-rag.grobid.base-url:http://localhost:8070}") String baseUrl,
                                @Value("${team-rag.grobid.timeout-seconds:20}") long timeoutSeconds) {
        this.webClient = builder.build();
        this.enabled = enabled;
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Optional<ParsedAcademicDocument> parsePdf(byte[] bytes, String filename) {
        if (!enabled || bytes == null || bytes.length == 0) {
            return Optional.empty();
        }
        try {
            MultipartBodyBuilder bodyBuilder = new MultipartBodyBuilder();
            bodyBuilder.part("input", new ByteArrayResource(bytes) {
                        @Override
                        public String getFilename() {
                            return filename == null || filename.isBlank() ? "document.pdf" : filename;
                        }
                    })
                    .contentType(MediaType.APPLICATION_PDF);

            String teiXml = webClient.post()
                    .uri(baseUrl + "/api/processFulltextDocument")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(bodyBuilder.build()))
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(timeout)
                    .block();

            if (teiXml == null || teiXml.isBlank()) {
                return Optional.empty();
            }
            ParsedAcademicDocument parsed = parseTei(teiXml);
            parsed.parser = "grobid";
            parsed.parserVersion = "tei";
            parsed.referencesRemoved = true;
            return parsed.sections.isEmpty() ? Optional.empty() : Optional.of(parsed);
        } catch (Exception e) {
            log.warn("GROBID parse failed, falling back to PDFBox | filename={} | error={}",
                    filename, e.getMessage());
            return Optional.empty();
        }
    }

    private ParsedAcademicDocument parseTei(String teiXml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

        Document document = factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(teiXml.getBytes(StandardCharsets.UTF_8)));
        document.getDocumentElement().normalize();

        ParsedAcademicDocument parsed = new ParsedAcademicDocument();
        parsed.title = firstText(document, "title");
        parsed.authors = extractAuthors(document);

        Element body = firstElement(document.getDocumentElement(), "body");
        if (body == null) {
            return parsed;
        }

        AtomicInteger sectionOrder = new AtomicInteger();
        List<Element> topDivs = directElements(body, "div");
        if (topDivs.isEmpty()) {
            String bodyText = normalizeWhitespace(body.getTextContent());
            if (!bodyText.isBlank()) {
                parsed.sections.add(new ParsedSection(
                        "sec_0", null, "Document", "Document", 1, 0, null, null, bodyText));
            }
            return parsed;
        }

        for (Element div : topDivs) {
            extractSection(div, null, "", 1, sectionOrder, parsed.sections);
        }
        return parsed;
    }

    private void extractSection(Element div,
                                String parentSectionId,
                                String parentPath,
                                int level,
                                AtomicInteger sectionOrder,
                                List<ParsedSection> sink) {
        String rawTitle = directText(div, "head");
        String title = rawTitle == null || rawTitle.isBlank()
                ? "Section " + (sectionOrder.get() + 1)
                : normalizeWhitespace(rawTitle);
        if (isReferencesTitle(title)) {
            return;
        }

        int order = sectionOrder.getAndIncrement();
        String sectionId = "sec_" + order;
        String sectionPath = parentPath == null || parentPath.isBlank() ? title : parentPath + " > " + title;
        String text = collectDirectParagraphText(div);
        if (!text.isBlank()) {
            sink.add(new ParsedSection(
                    sectionId,
                    parentSectionId,
                    title,
                    sectionPath,
                    level,
                    order,
                    null,
                    null,
                    text));
        }

        for (Element child : directElements(div, "div")) {
            extractSection(child, sectionId, sectionPath, level + 1, sectionOrder, sink);
        }
    }

    private String collectDirectParagraphText(Element section) {
        List<String> parts = new ArrayList<>();
        for (Node child = section.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element element = (Element) child;
            String local = element.getLocalName();
            if ("div".equals(local) || "head".equals(local)) {
                continue;
            }
            if ("p".equals(local) || "list".equals(local) || "quote".equals(local) || "formula".equals(local)) {
                String text = normalizeWhitespace(element.getTextContent());
                if (!text.isBlank()) {
                    parts.add(text);
                }
            }
        }
        if (parts.isEmpty()) {
            for (Element paragraph : directElements(section, "p")) {
                String text = normalizeWhitespace(paragraph.getTextContent());
                if (!text.isBlank()) {
                    parts.add(text);
                }
            }
        }
        return String.join("\n\n", parts);
    }

    private List<String> extractAuthors(Document document) {
        List<String> authors = new ArrayList<>();
        NodeList authorNodes = document.getElementsByTagNameNS(TEI_NS, "author");
        for (int i = 0; i < authorNodes.getLength(); i++) {
            String author = normalizeWhitespace(authorNodes.item(i).getTextContent());
            if (!author.isBlank() && authors.stream().noneMatch(author::equalsIgnoreCase)) {
                authors.add(author);
            }
        }
        return authors;
    }

    private String firstText(Document document, String tagName) {
        NodeList nodes = document.getElementsByTagNameNS(TEI_NS, tagName);
        for (int i = 0; i < nodes.getLength(); i++) {
            String text = normalizeWhitespace(nodes.item(i).getTextContent());
            if (!text.isBlank()) {
                return text;
            }
        }
        return null;
    }

    private Element firstElement(Element root, String localName) {
        NodeList nodes = root.getElementsByTagNameNS(TEI_NS, localName);
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element element) {
                return element;
            }
        }
        return null;
    }

    private String directText(Element parent, String localName) {
        for (Element element : directElements(parent, localName)) {
            String text = normalizeWhitespace(element.getTextContent());
            if (!text.isBlank()) {
                return text;
            }
        }
        return null;
    }

    private List<Element> directElements(Element parent, String localName) {
        List<Element> result = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.ELEMENT_NODE
                    && child instanceof Element element
                    && localName.equals(element.getLocalName())) {
                result.add(element);
            }
        }
        return result;
    }

    private boolean isReferencesTitle(String title) {
        if (title == null) {
            return false;
        }
        String lower = title.toLowerCase(Locale.ROOT).trim();
        return lower.equals("references")
                || lower.equals("reference")
                || lower.equals("bibliography")
                || lower.startsWith("references ")
                || lower.startsWith("bibliography ");
    }

    private String normalizeWhitespace(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "http://localhost:8070";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    public static class ParsedAcademicDocument {
        public String title;
        public List<String> authors = new ArrayList<>();
        public String parser;
        public String parserVersion;
        public boolean referencesRemoved;
        public List<ParsedSection> sections = new ArrayList<>();
    }

    public record ParsedSection(
            String sectionId,
            String parentSectionId,
            String title,
            String sectionPath,
            int level,
            int order,
            Integer pageStart,
            Integer pageEnd,
            String text) {
    }
}
