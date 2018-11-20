package org.zwobble.mammoth.internal.docx;

import static org.zwobble.mammoth.internal.docx.ReadResult.EMPTY_SUCCESS;
import static org.zwobble.mammoth.internal.docx.ReadResult.success;
import static org.zwobble.mammoth.internal.docx.Uris.uriToZipEntryName;
import static org.zwobble.mammoth.internal.util.Iterables.lazyFilter;
import static org.zwobble.mammoth.internal.util.Iterables.tryGetLast;
import static org.zwobble.mammoth.internal.util.Lists.list;
import static org.zwobble.mammoth.internal.util.Maps.entry;
import static org.zwobble.mammoth.internal.util.Maps.lookup;
import static org.zwobble.mammoth.internal.util.Sets.set;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.zwobble.mammoth.internal.archives.Archive;
import org.zwobble.mammoth.internal.archives.Archives;
import org.zwobble.mammoth.internal.documents.Bookmark;
import org.zwobble.mammoth.internal.documents.Break;
import org.zwobble.mammoth.internal.documents.CommentReference;
import org.zwobble.mammoth.internal.documents.DocumentElement;
import org.zwobble.mammoth.internal.documents.DocumentElementVisitor;
import org.zwobble.mammoth.internal.documents.Hyperlink;
import org.zwobble.mammoth.internal.documents.Image;
import org.zwobble.mammoth.internal.documents.NoteReference;
import org.zwobble.mammoth.internal.documents.NoteType;
import org.zwobble.mammoth.internal.documents.NumberingLevel;
import org.zwobble.mammoth.internal.documents.Paragraph;
import org.zwobble.mammoth.internal.documents.ParagraphIndent;
import org.zwobble.mammoth.internal.documents.Run;
import org.zwobble.mammoth.internal.documents.Style;
import org.zwobble.mammoth.internal.documents.Tab;
import org.zwobble.mammoth.internal.documents.Table;
import org.zwobble.mammoth.internal.documents.TableCell;
import org.zwobble.mammoth.internal.documents.TableOfContents;
import org.zwobble.mammoth.internal.documents.TableRow;
import org.zwobble.mammoth.internal.documents.Text;
import org.zwobble.mammoth.internal.documents.VerticalAlignment;
import org.zwobble.mammoth.internal.results.InternalResult;
import org.zwobble.mammoth.internal.util.Casts;
import org.zwobble.mammoth.internal.util.InputStreamSupplier;
import org.zwobble.mammoth.internal.util.Lists;
import org.zwobble.mammoth.internal.util.Optionals;
import org.zwobble.mammoth.internal.util.Queues;
import org.zwobble.mammoth.internal.xml.XmlElement;
import org.zwobble.mammoth.internal.xml.XmlElementLike;
import org.zwobble.mammoth.internal.xml.XmlElementList;
import org.zwobble.mammoth.internal.xml.XmlNode;

class StatefulBodyXmlReader {
    private static final Set<String> IMAGE_TYPES_SUPPORTED_BY_BROWSERS = set(
            "image/png", "image/gif", "image/jpeg", "image/svg+xml", "image/tiff");

    private final Styles styles;
    private final Numbering numbering;
    private final Relationships relationships;
    private final ContentTypes contentTypes;
    private final Archive file;
    private final FileReader fileReader;
    private final StringBuilder currentInstrText;
    private final Queue<ComplexField> complexFieldStack;

    private interface ComplexField {
        ComplexField UNKNOWN = new ComplexField() {};

        static ComplexField hyperlink(String href) {
            return new HyperlinkComplexField(href);
        }
    }

    private static class HyperlinkComplexField implements ComplexField {
        private final String href;

        private HyperlinkComplexField(String href) {
            this.href = href;
        }
    }

    StatefulBodyXmlReader(
            Styles styles,
            Numbering numbering,
            Relationships relationships,
            ContentTypes contentTypes,
            Archive file,
            FileReader fileReader
            )
    {
        this.styles = styles;
        this.numbering = numbering;
        this.relationships = relationships;
        this.contentTypes = contentTypes;
        this.file = file;
        this.fileReader = fileReader;
        this.currentInstrText = new StringBuilder();
        this.complexFieldStack = Queues.stack();
    }

    ReadResult readElement(XmlElement element) {
        switch (element.getName()) {
        case "w:t":
            return success(new Text(element.innerText()));
        case "w:r":
            return readRun(element);
        case "w:p":
            return readParagraph(element);

        case "w:fldChar":
            return readFieldChar(element);
        case "w:instrText":
            return readInstrText(element);

        case "w:tab":
            return success(Tab.TAB);
        case "w:noBreakHyphen":
            return success(new Text("\u2011"));
        case "w:br":
            return readBreak(element);

        case "w:tbl":
            return readTable(element);
        case "w:tr":
            return readTableRow(element);
        case "w:tc":
            return readTableCell(element);

        case "w:hyperlink":
            return readHyperlink(element);
        case "w:bookmarkStart":
            return readBookmark(element);
        case "w:footnoteReference":
            return readNoteReference(NoteType.FOOTNOTE, element);
        case "w:endnoteReference":
            return readNoteReference(NoteType.ENDNOTE, element);
        case "w:commentReference":
            return readCommentReference(element);

        case "w:pict":
            return readPict(element);

        case "v:imagedata":
            return readImagedata(element);

        case "wp:inline":
        case "wp:anchor":
            return readInline(element);

        case "w:sdt":
            return readSdt(element);

        case "w:ins":
        case "w:object":
        case "w:smartTag":
        case "w:drawing":
        case "v:group":
        case "v:rect":
        case "v:roundrect":
        case "v:shape":
        case "v:textbox":
        case "w:txbxContent":
            return readElements(element.getChildren());

        case "office-word:wrap":
        case "v:shadow":
        case "v:shapetype":
        case "w:bookmarkEnd":
        case "w:sectPr":
        case "w:proofErr":
        case "w:lastRenderedPageBreak":
        case "w:commentRangeStart":
        case "w:commentRangeEnd":
        case "w:del":
        case "w:footnoteRef":
        case "w:endnoteRef":
        case "w:annotationRef":
        case "w:pPr":
        case "w:rPr":
        case "w:tblPr":
        case "w:tblGrid":
        case "w:trPr":
        case "w:tcPr":
            return EMPTY_SUCCESS;

        default:
            String warning = "An unrecognised element was ignored: " + element.getName();
            return ReadResult.emptyWithWarning(warning);
        }
    }

    private ReadResult readRun(XmlElement element) {
        XmlElementLike properties = element.findChildOrEmpty("w:rPr");
        return ReadResult.map(
                readRunStyle(properties),
                readElements(element.getChildren()),
                (style, children) -> {
                    Optional<String> hyperlinkHref = currentHyperlinkHref();
                    if (hyperlinkHref.isPresent()) {
                        children = list(Hyperlink.href(hyperlinkHref.get(), Optional.empty(), children));
                    }

                    return new Run(
                            isBold(properties),
                            isItalic(properties),
                            isUnderline(properties),
                            isStrikethrough(properties),
                            isSmallCaps(properties),
                            readVerticalAlignment(properties),
                            style,
                            children
                            );
                }
                );
    }

    private Optional<String> currentHyperlinkHref() {
        return tryGetLast(lazyFilter(this.complexFieldStack, HyperlinkComplexField.class))
                .map(field -> field.href);
    }

    private boolean isBold(XmlElementLike properties) {
        return readBooleanElement(properties, "w:b");
    }

    private boolean isItalic(XmlElementLike properties) {
        return readBooleanElement(properties, "w:i");
    }

    private boolean isUnderline(XmlElementLike properties) {
        return readBooleanElement(properties, "w:u");
    }

    private boolean isStrikethrough(XmlElementLike properties) {
        return readBooleanElement(properties, "w:strike");
    }

    private boolean isSmallCaps(XmlElementLike properties) {
        return readBooleanElement(properties, "w:smallCaps");
    }

    private boolean readBooleanElement(XmlElementLike properties, String tagName) {
        return properties.findChild(tagName)
                .map(child -> child.getAttributeOrNone("w:val")
                        .map(value -> !value.equals("false") && !value.equals("0"))
                        .orElse(true))
                .orElse(false);
    }

    private VerticalAlignment readVerticalAlignment(XmlElementLike properties) {
        String verticalAlignment = readVal(properties, "w:vertAlign").orElse("");
        switch (verticalAlignment) {
        case "superscript":
            return VerticalAlignment.SUPERSCRIPT;
        case "subscript":
            return VerticalAlignment.SUBSCRIPT;
        default:
            // TODO: warn if set?
            return VerticalAlignment.BASELINE;
        }
    }

    private InternalResult<Optional<Style>> readRunStyle(XmlElementLike properties) {
        return readStyle(properties, "w:rStyle", "Run", styles::findCharacterStyleById);
    }

    ReadResult readElements(Iterable<XmlNode> nodes) {
        return ReadResult.flatMap(lazyFilter(nodes, XmlElement.class), this::readElement);
    }

    private ReadResult readParagraph(XmlElement element) {
        XmlElementLike properties = element.findChildOrEmpty("w:pPr");
        Optional<NumberingLevel> numbering = readNumbering(properties);
        ParagraphIndent indent = readParagraphIndent(properties);
        return ReadResult.map(
                readParagraphStyle(properties),
                readElements(element.getChildren()),
                (style, children) -> new Paragraph(style, numbering, indent, children)).appendExtra();
    }

    private ReadResult readFieldChar(XmlElement element) {
        String type = element.getAttributeOrNone("w:fldCharType").orElse("");
        if (type.equals("begin")) {
            complexFieldStack.add(ComplexField.UNKNOWN);
            currentInstrText.setLength(0);
        } else if (type.equals("end")) {
            complexFieldStack.remove();
        } else if (type.equals("separate")) {
            String instrText = currentInstrText.toString();
            ComplexField complexField = parseHyperlinkFieldCode(instrText)
                    .map(href -> ComplexField.hyperlink(href))
                    .orElse(ComplexField.UNKNOWN);
            complexFieldStack.remove();
            complexFieldStack.add(complexField);
        }
        return ReadResult.EMPTY_SUCCESS;
    }

    private ReadResult readInstrText(XmlElement element) {
        currentInstrText.append(element.innerText());
        return ReadResult.EMPTY_SUCCESS;
    }

    private Optional<String> parseHyperlinkFieldCode(String instrText) {
        Pattern pattern = Pattern.compile("\\s*HYPERLINK \"(.*)\"");
        Matcher matcher = pattern.matcher(instrText);
        if (matcher.lookingAt()) {
            return Optional.of(matcher.group(1));
        } else {
            return Optional.empty();
        }
    }

    private InternalResult<Optional<Style>> readParagraphStyle(XmlElementLike properties) {
        return readStyle(properties, "w:pStyle", "Paragraph", styles::findParagraphStyleById);
    }

    private InternalResult<Optional<Style>> readStyle(
            XmlElementLike properties,
            String styleTagName,
            String styleType,
            Function<String, Optional<Style>> findStyleById)
    {
        return readVal(properties, styleTagName)
                .map(styleId -> findStyleById(styleType, styleId, findStyleById))
                .orElse(InternalResult.empty());
    }

    private InternalResult<Optional<Style>> findStyleById(
            String styleType,
            String styleId,
            Function<String, Optional<Style>> findStyleById)
    {
        Optional<Style> style = findStyleById.apply(styleId);
        if (style.isPresent()) {
            return InternalResult.success(style);
        } else {
            return new InternalResult<>(
                    Optional.of(new Style(styleId, Optional.empty())),
                    list(styleType + " style with ID " + styleId + " was referenced but not defined in the document"));
        }

    }

    private Optional<NumberingLevel> readNumbering(XmlElementLike properties) {
        XmlElementLike numberingProperties = properties.findChildOrEmpty("w:numPr");
        return Optionals.flatMap(
                readVal(numberingProperties, "w:numId"),
                readVal(numberingProperties, "w:ilvl"),
                numbering::findLevel);
    }

    private ParagraphIndent readParagraphIndent(XmlElementLike properties) {
        XmlElementLike indent = properties.findChildOrEmpty("w:ind");
        return new ParagraphIndent(
                Optionals.first(
                        indent.getAttributeOrNone("w:start"),
                        indent.getAttributeOrNone("w:left")
                        ),
                Optionals.first(
                        indent.getAttributeOrNone("w:end"),
                        indent.getAttributeOrNone("w:right")
                        ),
                indent.getAttributeOrNone("w:firstLine"),
                indent.getAttributeOrNone("w:hanging")
                );
    }

    private ReadResult readBreak(XmlElement element) {
        String breakType = element.getAttributeOrNone("w:type").orElse("textWrapping");
        switch (breakType) {
        case "textWrapping":
            return success(Break.LINE_BREAK);
        case "page":
            return success(Break.PAGE_BREAK);
        case "column":
            return success(Break.COLUMN_BREAK);
        default:
            return ReadResult.emptyWithWarning("Unsupported break type: " + breakType);
        }
    }

    private ReadResult readTable(XmlElement element) {
        XmlElementLike properties = element.findChildOrEmpty("w:tblPr");
        return ReadResult.map(
                readTableStyle(properties),
                readElements(element.getChildren())
                .flatMap(this::calculateRowspans),

                Table::new
                );
    }

    private InternalResult<Optional<Style>> readTableStyle(XmlElementLike properties) {
        return readStyle(properties, "w:tblStyle", "Table", styles::findTableStyleById);
    }

    private ReadResult calculateRowspans(List<DocumentElement> rows) {
        Optional<String> error = checkTableRows(rows);
        if (error.isPresent()) {
            return ReadResult.withWarning(rows, error.get());
        }

        Map<Map.Entry<Integer, Integer>, Integer> rowspans = new HashMap<>();
        Set<Map.Entry<Integer, Integer>> merged = new HashSet<>();

        Map<Integer, Map.Entry<Integer, Integer>> lastCellForColumn = new HashMap<>();
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex += 1) {
            TableRow row = (TableRow) rows.get(rowIndex);
            int columnIndex = 0;
            for (int cellIndex = 0; cellIndex < row.getChildren().size(); cellIndex += 1) {
                UnmergedTableCell cell = (UnmergedTableCell) row.getChildren().get(cellIndex);
                Optional<Map.Entry<Integer, Integer>> spanningCell = lookup(lastCellForColumn, columnIndex);
                Map.Entry<Integer, Integer> position = entry(rowIndex, cellIndex);
                if (cell.vmerge && spanningCell.isPresent()) {
                    rowspans.put(spanningCell.get(), lookup(rowspans, spanningCell.get()).get() + 1);
                    merged.add(position);
                } else {
                    lastCellForColumn.put(columnIndex, position);
                    rowspans.put(position, 1);
                }
                columnIndex += cell.colspan;
            }
        }

        return success(Lists.eagerMapWithIndex(rows, (rowIndex, rowElement) -> {
            TableRow row = (TableRow) rowElement;

            List<DocumentElement> mergedCells = new ArrayList<>();
            for (int cellIndex = 0; cellIndex < row.getChildren().size(); cellIndex += 1) {
                UnmergedTableCell cell = (UnmergedTableCell) row.getChildren().get(cellIndex);
                Map.Entry<Integer, Integer> position = entry(rowIndex, cellIndex);
                if (!merged.contains(position)) {
                    mergedCells.add(new TableCell(
                            lookup(rowspans, position).get(),
                            cell.colspan,
                            cell.children
                            ));
                }
            }

            return new TableRow(mergedCells, row.isHeader());
        }));
    }

    private Optional<String> checkTableRows(List<DocumentElement> rows) {
        for (DocumentElement rowElement : rows) {
            Optional<TableRow> row = Casts.tryCast(TableRow.class, rowElement);
            if (!row.isPresent()) {
                return Optional.of("unexpected non-row element in table, cell merging may be incorrect");
            } else {
                for (DocumentElement cell : row.get().getChildren()) {
                    if (!(cell instanceof UnmergedTableCell)) {
                        return Optional.of("unexpected non-cell element in table row, cell merging may be incorrect");
                    }
                }
            }
        }
        return Optional.empty();
    }

    private ReadResult readTableRow(XmlElement element) {
        XmlElementLike properties = element.findChildOrEmpty("w:trPr");
        boolean isHeader = properties.hasChild("w:tblHeader");
        return readElements(element.getChildren())
                .map(children -> new TableRow(children, isHeader));
    }

    private ReadResult readTableCell(XmlElement element) {
        XmlElementLike properties = element.findChildOrEmpty("w:tcPr");
        Optional<String> gridSpan = properties
                .findChildOrEmpty("w:gridSpan")
                .getAttributeOrNone("w:val");
        int colspan = gridSpan.map(Integer::parseInt).orElse(1);
        return readElements(element.getChildren())
                .map(children -> new UnmergedTableCell(readVmerge(properties), colspan, children));
    }

    private boolean readVmerge(XmlElementLike properties) {
        return properties.findChild("w:vMerge")
                .map(element -> element.getAttributeOrNone("w:val").map(val -> val.equals("continue")).orElse(true))
                .orElse(false);
    }

    private static class UnmergedTableCell implements DocumentElement {
        private final boolean vmerge;
        private final int colspan;
        private final List<DocumentElement> children;

        private UnmergedTableCell(boolean vmerge, int colspan, List<DocumentElement> children) {
            this.vmerge = vmerge;
            this.colspan = colspan;
            this.children = children;
        }

        @Override
        public <T, U> T accept(DocumentElementVisitor<T, U> visitor, U context) {
            return visitor.visit(new TableCell(1, colspan, children), context);
        }
    }

    private ReadResult readHyperlink(XmlElement element) {
        Optional<String> relationshipId = element.getAttributeOrNone("r:id");
        Optional<String> anchor = element.getAttributeOrNone("w:anchor");
        Optional<String> targetFrame = element.getAttributeOrNone("w:tgtFrame")
                .filter(value -> !value.isEmpty());
        ReadResult childrenResult = readElements(element.getChildren());

        if (relationshipId.isPresent()) {
            String targetHref = relationships.findTargetByRelationshipId(relationshipId.get());
            String href = anchor.map(fragment -> Uris.replaceFragment(targetHref, anchor.get()))
                    .orElse(targetHref);
            return childrenResult.map(children ->
            Hyperlink.href(href, targetFrame, children)
                    );
        } else if (anchor.isPresent()) {
            return childrenResult.map(children ->
            Hyperlink.anchor(anchor.get(), targetFrame, children)
                    );
        } else {
            return childrenResult;
        }
    }

    private ReadResult readBookmark(XmlElement element) {
        String name = element.getAttribute("w:name");
        if (name.equals("_GoBack")) {
            return ReadResult.EMPTY_SUCCESS;
        } else {
            return success(new Bookmark(name));
        }
    }

    private ReadResult readNoteReference(NoteType noteType, XmlElement element) {
        String noteId = element.getAttribute("w:id");
        return success(new NoteReference(noteType, noteId));
    }

    private ReadResult readCommentReference(XmlElement element) {
        String commentId = element.getAttribute("w:id");
        return success(new CommentReference(commentId));
    }

    private ReadResult readPict(XmlElement element) {
        return readElements(element.getChildren()).toExtra();
    }

    private ReadResult readImagedata(XmlElement element) {
        return element.getAttributeOrNone("r:id")
                .map(relationshipId -> {
                    Optional<String> title = element.getAttributeOrNone("o:title");
                    String imagePath = relationshipIdToDocxPath(relationshipId);
                    return readImage(imagePath, title, () -> Archives.getInputStream(file, imagePath));
                })
                .orElse(ReadResult.emptyWithWarning("A v:imagedata element without a relationship ID was ignored"));
    }

    private ReadResult readInline(XmlElement element) {
        XmlElementLike properties = element.findChildOrEmpty("wp:docPr");
        Optional<String> altText = Optionals.first(
                properties.getAttributeOrNone("descr").filter(description -> !description.trim().isEmpty()),
                properties.getAttributeOrNone("title")
                );
        XmlElementList blips = element.findChildren("a:graphic")
                .findChildren("a:graphicData")
                .findChildren("pic:pic")
                .findChildren("pic:blipFill")
                .findChildren("a:blip");
        return readBlips(blips, altText);
    }

    private ReadResult readBlips(XmlElementList blips, Optional<String> altText) {
        return ReadResult.flatMap(blips, blip -> readBlip(blip, altText));
    }

    private ReadResult readBlip(XmlElement blip, Optional<String> altText) {
        Optional<String> embedRelationshipId = blip.getAttributeOrNone("r:embed");
        Optional<String> linkRelationshipId = blip.getAttributeOrNone("r:link");
        if (embedRelationshipId.isPresent()) {
            String imagePath = relationshipIdToDocxPath(embedRelationshipId.get());
            return readImage(imagePath, altText, () -> Archives.getInputStream(file, imagePath));
        } else if (linkRelationshipId.isPresent()) {
            String imagePath = relationships.findTargetByRelationshipId(linkRelationshipId.get());
            return readImage(imagePath, altText, () -> fileReader.getInputStream(imagePath));
        } else {
            // TODO: emit warning
            return ReadResult.EMPTY_SUCCESS;
        }
    }

    private ReadResult readImage(String imagePath, Optional<String> altText, InputStreamSupplier open) {
        Optional<String> contentType = contentTypes.findContentType(imagePath);
        Image image = new Image(altText, contentType, open);

        String contentTypeString = contentType.orElse("(unknown)");
        if (IMAGE_TYPES_SUPPORTED_BY_BROWSERS.contains(contentTypeString)) {
            return success(image);
        } else {
            return ReadResult.withWarning(image, "Image of type " + contentTypeString + " is unlikely to display in web browsers");
        }
    }

    private ReadResult readSdt(XmlElement element) {
        Optional<XmlElement> docPartObj = element.findChild("w:sdtPr").flatMap(el -> el.findChild("w:docPartObj"));
        if (docPartObj.isPresent()) {
            ReadResult childrenResult = readElements(element.findChildOrEmpty("w:sdtContent").getChildren());
            return childrenResult.map(children -> new TableOfContents(children));
        } else {
            return readElements(element.findChildOrEmpty("w:sdtContent").getChildren());
        }
    }

    private String relationshipIdToDocxPath(String relationshipId) {
        String target = relationships.findTargetByRelationshipId(relationshipId);
        return uriToZipEntryName("word", target);
    }

    private Optional<String> readVal(XmlElementLike element, String name) {
        return element.findChildOrEmpty(name).getAttributeOrNone("w:val");
    }
}
