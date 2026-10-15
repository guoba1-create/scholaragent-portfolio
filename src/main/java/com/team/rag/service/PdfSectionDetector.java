package com.team.rag.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PDF 章节检测器 —— 从 PDF 文件中识别文档的章节结构。
 *
 * <p>采用两种策略（优先级递减）：</p>
 * <ol>
 *   <li><b>Outline/Bookmark 提取</b>：读取 PDF 内置的书签目录，零成本获取章节标题和页码映射</li>
 *   <li><b>学术论文标题正则</b>：在全文中匹配 "1. Introduction"、"Abstract" 等常见学术论文标题模式</li>
 * </ol>
 *
 * <p>如果两种策略都失败，返回空列表，调用方应回退到逐页切分。</p>
 *
 * <p>在系统中的位置：DocumentService → PdfSectionDetector.detect() → 返回章节结构 → 按章节合并页面文本</p>
 *
 * @see DocumentService — 文档处理核心服务，调用本类进行章节检测
 */
@Service
public class PdfSectionDetector {

    private static final Logger log = LoggerFactory.getLogger(PdfSectionDetector.class);

    /**
     * 学术论文常见章节标题正则（英文）。
     * 匹配 "1 Introduction"、"3.2 Methods"、"Abstract" 等格式。
     */
    private static final Pattern SECTION_NUMBERED = Pattern.compile(
            "^\\s*(\\d{1,2}\\.?\\d{0,2})\\s+([A-Z][a-zA-Z\\s&-]{2,60})",
            Pattern.MULTILINE);

    /**
     * 无编号的常见章节名（英文）。
     */
    private static final Pattern SECTION_NAMED = Pattern.compile(
            "^\\s*(Abstract|Introduction|Related\\s+Work|Background|Methodology|Methods?|"
                    + "Experiments?|Results?|Discussion|Conclusions?|References|Acknowledgments?|Appendix)\\s*$",
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    /**
     * 中文论文章节标题正则。
     * 匹配 "第三章 实验"、"3.2 方法"、"摘要" 等格式。
     */
    private static final Pattern SECTION_CHINESE = Pattern.compile(
            "^\\s*(?:第[一二三四五六七八九十百]+[章节部分篇]\\s*[:：]?\\s*(.*)|"
                    + "(\\d{1,2}\\.?\\d{0,2})\\s+([\\u4e00-\\u9fa5]{2,20})|"
                    + "(摘要|引言|相关工作|方法|实验|结果|讨论|结论|参考文献|致谢|附录))\\s*$",
            Pattern.MULTILINE);

    /**
     * 从 PDF 字节流中检测章节结构。
     *
     * <p>先尝试 Outline 提取（零成本），失败后尝试正则匹配。</p>
     *
     * @param pdfBytes PDF 文件的原始字节
     * @return 章节列表（按页码排序），空列表表示检测失败，应回退到逐页切分
     */
    public List<DetectedSection> detect(byte[] pdfBytes) {
        // 策略 1：Outline/Bookmark
        List<DetectedSection> fromOutline = detectByOutline(pdfBytes);
        if (!fromOutline.isEmpty()) {
            log.info("Section detection: outline, {} sections found", fromOutline.size());
            return fromOutline;
        }

        // 策略 2：正则匹配
        List<DetectedSection> fromRegex = detectByRegex(pdfBytes);
        if (!fromRegex.isEmpty()) {
            log.info("Section detection: regex, {} sections found", fromRegex.size());
            return fromRegex;
        }

        log.info("Section detection: fallback (no sections detected)");
        return Collections.emptyList();
    }

    /**
     * 通过 PDF Outline/Bookmark 提取章节结构。
     *
     * <p>PDFBox 3.x API：PDDocument → getDocumentCatalog() → getOutline()
     * → 遍历顶层 PDOutlineItem 子节点。</p>
     */
    private List<DetectedSection> detectByOutline(byte[] pdfBytes) {
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("outline-", ".pdf");
            Files.write(tempFile, pdfBytes);

            try (PDDocument doc = Loader.loadPDF(tempFile.toFile())) {
                PDDocumentOutline outline = doc.getDocumentCatalog().getDocumentOutline();
                if (outline == null) {
                    return Collections.emptyList();
                }

                List<DetectedSection> sections = new ArrayList<>();
                PDOutlineItem current = outline.getFirstChild();
                while (current != null) {
                    String title = current.getTitle();
                    if (title != null && !title.isBlank()) {
                        int pageNumber = resolvePageNumber(current, doc);
                        if (pageNumber > 0) {
                            sections.add(new DetectedSection(title.trim(), pageNumber, -1));
                        }
                    }
                    current = current.getNextSibling();
                }

                if (sections.isEmpty()) {
                    return Collections.emptyList();
                }

                // 按页码排序
                sections.sort((a, b) -> Integer.compare(a.startPage, b.startPage));

                // 填充 endPage：每个章节的结束页 = 下一个章节的起始页 - 1
                for (int i = 0; i < sections.size() - 1; i++) {
                    sections.set(i, new DetectedSection(
                            sections.get(i).title,
                            sections.get(i).startPage,
                            sections.get(i + 1).startPage - 1));
                }
                // 最后一个章节的结束页 = 文档最后一页
                DetectedSection last = sections.get(sections.size() - 1);
                sections.set(sections.size() - 1, new DetectedSection(
                        last.title, last.startPage, doc.getNumberOfPages()));

                return sections;
            }
        } catch (Exception e) {
            log.debug("Outline detection failed: {}", e.getMessage());
            return Collections.emptyList();
        } finally {
            if (tempFile != null) {
                try { Files.deleteIfExists(tempFile); } catch (IOException ignored) {}
            }
        }
    }

    /**
     * 解析 OutlineItem 的目标页码。
     *
     * <p>使用 PDFBox 3.x 的 findDestinationPage() 获取目标 PDPage 对象，
     * 再通过文档页面列表查找其 1-based 页码。</p>
     */
    private int resolvePageNumber(PDOutlineItem item, PDDocument doc) {
        try {
            PDPage targetPage = item.findDestinationPage(doc);
            if (targetPage != null) {
                for (int i = 0; i < doc.getNumberOfPages(); i++) {
                    if (doc.getPage(i) == targetPage) {
                        return i + 1;
                    }
                }
            }
        } catch (Exception e) {
            log.trace("Cannot resolve page for outline item '{}': {}", item.getTitle(), e.getMessage());
        }
        return -1;
    }

    /**
     * 通过正则匹配学术论文标题来检测章节结构。
     *
     * <p>先提取全文（按页记录行号与页码的映射），再逐行匹配标题模式。</p>
     */
    private List<DetectedSection> detectByRegex(byte[] pdfBytes) {
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("regex-", ".pdf");
            Files.write(tempFile, pdfBytes);

            try (PDDocument doc = Loader.loadPDF(tempFile.toFile())) {
                int totalPages = doc.getNumberOfPages();
                // 提取全文并记录每行所在的页码
                List<LineWithPage> lines = extractLinesWithPage(doc, totalPages);
                if (lines.isEmpty()) {
                    return Collections.emptyList();
                }

                List<DetectedSection> sections = new ArrayList<>();
                for (LineWithPage line : lines) {
                    String trimmed = line.text.trim();
                    if (trimmed.isEmpty() || trimmed.length() > 80) {
                        continue;
                    }

                    String title = matchSectionTitle(trimmed);
                    if (title != null) {
                        // 避免重复添加相同标题
                        boolean isDuplicate = sections.stream()
                                .anyMatch(s -> s.title.equalsIgnoreCase(title)
                                        && Math.abs(s.startPage - line.page) <= 1);
                        if (!isDuplicate) {
                            sections.add(new DetectedSection(title, line.page, -1));
                        }
                    }
                }

                if (sections.size() < 2) {
                    // 少于 2 个章节，可能是误检，不使用正则结果
                    return Collections.emptyList();
                }

                // 按页码排序并填充 endPage
                sections.sort((a, b) -> Integer.compare(a.startPage, b.startPage));
                for (int i = 0; i < sections.size() - 1; i++) {
                    sections.set(i, new DetectedSection(
                            sections.get(i).title,
                            sections.get(i).startPage,
                            sections.get(i + 1).startPage - 1));
                }
                DetectedSection last = sections.get(sections.size() - 1);
                sections.set(sections.size() - 1, new DetectedSection(
                        last.title, last.startPage, totalPages));

                return sections;
            }
        } catch (Exception e) {
            log.debug("Regex section detection failed: {}", e.getMessage());
            return Collections.emptyList();
        } finally {
            if (tempFile != null) {
                try { Files.deleteIfExists(tempFile); } catch (IOException ignored) {}
            }
        }
    }

    /**
     * 检查一行文本是否匹配学术论文章节标题模式。
     *
     * @return 匹配到的标准化标题，null 表示不匹配
     */
    private String matchSectionTitle(String line) {
        // 英文带编号："1 Introduction"、"3.2 Methods"
        Matcher m1 = SECTION_NUMBERED.matcher(line);
        if (m1.find()) {
            String number = m1.group(1);
            String name = m1.group(2).trim();
            return number + " " + name;
        }

        // 英文无编号："Abstract"、"Related Work"
        Matcher m2 = SECTION_NAMED.matcher(line);
        if (m2.find()) {
            return m2.group(1).trim();
        }

        // 中文："第三章 实验"、"3.2 方法"、"摘要"
        Matcher m3 = SECTION_CHINESE.matcher(line);
        if (m3.find()) {
            if (m3.group(1) != null) {
                return m3.group(1).trim();
            }
            if (m3.group(4) != null) {
                return m3.group(4).trim();
            }
        }

        return null;
    }

    /**
     * 逐页提取文本行，并记录每行所在的页码。
     */
    private List<LineWithPage> extractLinesWithPage(PDDocument doc, int totalPages) throws IOException {
        List<LineWithPage> result = new ArrayList<>();
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        stripper.setLineSeparator("\n");

        for (int page = 1; page <= totalPages; page++) {
            stripper.setStartPage(page);
            stripper.setEndPage(page);
            String pageText = stripper.getText(doc);
            if (pageText == null || pageText.isBlank()) {
                continue;
            }
            for (String line : pageText.split("\n")) {
                result.add(new LineWithPage(line, page));
            }
        }
        return result;
    }

    /**
     * 检测到的章节结构。
     *
     * @param title     章节标题（如 "1. Introduction"、"Abstract"）
     * @param startPage 起始页码（1-based）
     * @param endPage   结束页码（1-based），-1 表示未确定
     */
    public record DetectedSection(String title, int startPage, int endPage) {}

    /** 文本行与所在页码的映射。 */
    private record LineWithPage(String text, int page) {}
}
