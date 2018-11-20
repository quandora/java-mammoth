package org.zwobble.mammoth.internal.conversion;

import static org.zwobble.mammoth.internal.util.Casts.tryCast;
import static org.zwobble.mammoth.internal.util.Iterables.findIndex;
import static org.zwobble.mammoth.internal.util.Lists.cons;
import static org.zwobble.mammoth.internal.util.Lists.eagerConcat;
import static org.zwobble.mammoth.internal.util.Lists.eagerFlatMap;
import static org.zwobble.mammoth.internal.util.Lists.eagerMap;
import static org.zwobble.mammoth.internal.util.Lists.list;
import static org.zwobble.mammoth.internal.util.Maps.lookup;
import static org.zwobble.mammoth.internal.util.Maps.map;
import static org.zwobble.mammoth.internal.util.Maps.mutableMap;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import org.zwobble.mammoth.images.ImageConverter;
import org.zwobble.mammoth.internal.documents.Bookmark;
import org.zwobble.mammoth.internal.documents.Break;
import org.zwobble.mammoth.internal.documents.Comment;
import org.zwobble.mammoth.internal.documents.CommentReference;
import org.zwobble.mammoth.internal.documents.Document;
import org.zwobble.mammoth.internal.documents.DocumentElement;
import org.zwobble.mammoth.internal.documents.DocumentElementVisitor;
import org.zwobble.mammoth.internal.documents.HasChildren;
import org.zwobble.mammoth.internal.documents.Hyperlink;
import org.zwobble.mammoth.internal.documents.Image;
import org.zwobble.mammoth.internal.documents.Note;
import org.zwobble.mammoth.internal.documents.NoteReference;
import org.zwobble.mammoth.internal.documents.NoteType;
import org.zwobble.mammoth.internal.documents.Paragraph;
import org.zwobble.mammoth.internal.documents.Run;
import org.zwobble.mammoth.internal.documents.Tab;
import org.zwobble.mammoth.internal.documents.Table;
import org.zwobble.mammoth.internal.documents.TableCell;
import org.zwobble.mammoth.internal.documents.TableOfContents;
import org.zwobble.mammoth.internal.documents.TableRow;
import org.zwobble.mammoth.internal.documents.Text;
import org.zwobble.mammoth.internal.documents.VerticalAlignment;
import org.zwobble.mammoth.internal.html.Html;
import org.zwobble.mammoth.internal.html.HtmlNode;
import org.zwobble.mammoth.internal.results.InternalResult;
import org.zwobble.mammoth.internal.styles.HtmlPath;
import org.zwobble.mammoth.internal.styles.StyleMap;
import org.zwobble.mammoth.internal.util.Lists;
import org.zwobble.mammoth.internal.util.Maps;

public class DocumentToHtml {
    public static InternalResult<List<HtmlNode>> convertToHtml(Document document, DocumentToHtmlOptions options) {
        DocumentToHtml documentConverter = new DocumentToHtml(options, document.getComments());
        return new InternalResult<>(
                documentConverter.convertToHtml(document, INITIAL_CONTEXT),
                documentConverter.warnings);
    }

    private static List<Note> findNotes(Document document, Iterable<NoteReference> noteReferences) {
        return eagerMap(
                noteReferences,
                // TODO: handle missing notes
                reference -> document.getNotes().findNote(reference.getNoteType(), reference.getNoteId()).get());
    }

    public static InternalResult<List<HtmlNode>> convertToHtml(DocumentElement element, DocumentToHtmlOptions options) {
        DocumentToHtml documentConverter = new DocumentToHtml(options, list());
        return new InternalResult<>(
                documentConverter.convertToHtml(element, INITIAL_CONTEXT),
                documentConverter.warnings);
    }

    private static class ReferencedComment {
        private final String label;
        private final Comment comment;

        private ReferencedComment(String label, Comment comment) {
            this.label = label;
            this.comment = comment;
        }
    }

    private final String idPrefix;
    private final boolean preserveEmptyParagraphs;
    private final StyleMap styleMap;
    private final ImageConverter.ImgElement imageConverter;
    private final Map<String, Comment> comments;
    private final List<NoteReference> noteReferences = new ArrayList<>();
    private final List<ReferencedComment> referencedComments = new ArrayList<>();
    private final Set<String> warnings = new HashSet<>();
    private final String tocClass;

    private static final Context INITIAL_CONTEXT = new Context(false);

    private static class Context {
        private final boolean isHeader;

        Context(boolean isHeader) {
            this.isHeader = isHeader;
        }

        Context isHeader(boolean isHeader) {
            return new Context(isHeader);
        }
    }

    private DocumentToHtml(DocumentToHtmlOptions options, List<Comment> comments) {
        this.idPrefix = options.idPrefix();
        this.preserveEmptyParagraphs = options.shouldPreserveEmptyParagraphs();
        this.styleMap = options.styleMap();
        this.imageConverter = options.imageConverter();
        this.comments = Maps.toMapWithKey(comments, Comment::getCommentId);
        this.tocClass = options.tocClass();
    }

    private List<HtmlNode> convertToHtml(Document document, Context context) {
        List<HtmlNode> mainBody = convertChildrenToHtml(document, context);
        // TODO: can you have note references inside a note?
        List<Note> notes = findNotes(document, noteReferences);

        List<HtmlNode> noteNodes = notes.isEmpty()
                ? list()
                        : list(Html.element("ol", eagerMap(notes, note -> convertToHtml(note, context))));

                List<HtmlNode> commentNodes = referencedComments.isEmpty()
                        ? list()
                                : list(Html.element("dl", eagerFlatMap(referencedComments, comment -> convertToHtml(comment, context))));

                        return eagerConcat(mainBody, noteNodes, commentNodes);
    }

    private HtmlNode convertToHtml(Note note, Context context) {
        String id = generateNoteHtmlId(note.getNoteType(), note.getId());
        String referenceId = generateNoteRefHtmlId(note.getNoteType(), note.getId());
        List<HtmlNode> noteBody = convertToHtml(note.getBody(), context);
        // TODO: we probably want this to collapse more eagerly than other collapsible elements
        // -- for instance, any paragraph will probably do, regardless of attributes. (Possible other elements will do too.)
        HtmlNode backLink = Html.collapsibleElement("p", list(
                Html.text(" "),
                Html.element("a", map("href", "#" + referenceId), list(Html.text("↑")))));
        return Html.element("li", map("id", id), eagerConcat(noteBody, list(backLink)));
    }

    private List<HtmlNode> convertToHtml(ReferencedComment referencedComment, Context context) {
        // TODO: remove duplication with notes
        String commentId = referencedComment.comment.getCommentId();
        List<HtmlNode> body = convertToHtml(referencedComment.comment.getBody(), context);
        // TODO: we probably want this to collapse more eagerly than other collapsible elements
        // -- for instance, any paragraph will probably do, regardless of attributes. (Possible other elements will do too.)
        HtmlNode backLink = Html.collapsibleElement("p", list(
                Html.text(" "),
                Html.element("a", map("href", "#" + generateReferenceHtmlId("comment", commentId)), list(Html.text("↑")))));

        return list(
                Html.element(
                        "dt",
                        map("id", generateReferentHtmlId("comment", commentId)),
                        list(Html.text("Comment " + referencedComment.label))),
                Html.element("dd",
                        eagerConcat(body, list(backLink))));
    }

    private List<HtmlNode> convertToHtml(List<DocumentElement> elements, Context context) {
        return eagerFlatMap(
                elements,
                element -> convertToHtml(element, context)
                );
    }

    private List<HtmlNode> convertChildrenToHtml(HasChildren element, Context context) {
        return convertToHtml(element.getChildren(), context);
    }

    private class ElementConverterVisitor implements DocumentElementVisitor<List<HtmlNode>, Context> {
        @Override
        public List<HtmlNode> visit(Paragraph paragraph, Context context) {
            Supplier<List<HtmlNode>> children = () -> {
                List<HtmlNode> content = convertChildrenToHtml(paragraph, context);
                return preserveEmptyParagraphs ? cons(Html.FORCE_WRITE, content) : content;
            };
            HtmlPath mapping = styleMap.getParagraphHtmlPath(paragraph)
                    .orElseGet(() -> {
                        if (paragraph.getStyle().isPresent()) {
                            warnings.add("Unrecognised paragraph style: " + paragraph.getStyle().get().describe());
                        }
                        return HtmlPath.element("p");
                    });
            return mapping.wrap(children).get();
        }

        @Override
        public List<HtmlNode> visit(Run run, Context context) {
            Supplier<List<HtmlNode>> nodes = () -> convertChildrenToHtml(run, context);
            if (run.isSmallCaps()) {
                nodes = styleMap.getSmallCaps().orElse(HtmlPath.EMPTY).wrap(nodes);
            }
            if (run.isStrikethrough()) {
                nodes = styleMap.getStrikethrough().orElse(HtmlPath.collapsibleElement("s")).wrap(nodes);
            }
            if (run.isUnderline()) {
                nodes = styleMap.getUnderline().orElse(HtmlPath.EMPTY).wrap(nodes);
            }
            if (run.getVerticalAlignment() == VerticalAlignment.SUBSCRIPT) {
                nodes = HtmlPath.collapsibleElement("sub").wrap(nodes);
            }
            if (run.getVerticalAlignment() == VerticalAlignment.SUPERSCRIPT) {
                nodes = HtmlPath.collapsibleElement("sup").wrap(nodes);
            }
            if (run.isItalic()) {
                nodes = styleMap.getItalic().orElse(HtmlPath.collapsibleElement("em")).wrap(nodes);
            }
            if (run.isBold()) {
                nodes = styleMap.getBold().orElse(HtmlPath.collapsibleElement("strong")).wrap(nodes);
            }
            HtmlPath mapping = styleMap.getRunHtmlPath(run)
                    .orElseGet(() -> {
                        if (run.getStyle().isPresent()) {
                            warnings.add("Unrecognised run style: " + run.getStyle().get().describe());
                        }
                        return HtmlPath.EMPTY;
                    });
            return mapping.wrap(nodes).get();
        }

        @Override
        public List<HtmlNode> visit(Text text, Context context) {
            if (text.getValue().isEmpty()) {
                return list();
            } else {
                return list(Html.text(text.getValue()));
            }
        }

        @Override
        public List<HtmlNode> visit(Tab tab, Context context) {
            return list(Html.text("\t"));
        }

        @Override
        public List<HtmlNode> visit(Break breakElement, Context context) {
            HtmlPath mapping = styleMap.getBreakHtmlPath(breakElement)
                    .orElseGet(() -> {
                        if (breakElement.getType() == Break.Type.LINE) {
                            return HtmlPath.element("br");
                        } else {
                            return HtmlPath.EMPTY;
                        }
                    });
            return mapping.wrap(() -> list()).get();
        }

        @Override
        public List<HtmlNode> visit(Table table, Context context) {
            HtmlPath mapping = styleMap.getTableHtmlPath(table)
                    .orElse(HtmlPath.element("table"));
            return mapping.wrap(() -> generateTableChildren(table, context)).get();
        }

        private List<HtmlNode> generateTableChildren(Table table, Context context) {
            int bodyIndex = findIndex(table.getChildren(), child -> !isHeader(child))
                    .orElse(table.getChildren().size());
            if (bodyIndex == 0) {
                return convertToHtml(table.getChildren(), context.isHeader(false));
            } else {
                List<HtmlNode> headRows = convertToHtml(
                        table.getChildren().subList(0, bodyIndex),
                        context.isHeader(true)
                        );
                List<HtmlNode> bodyRows = convertToHtml(
                        table.getChildren().subList(bodyIndex, table.getChildren().size()),
                        context.isHeader(false)
                        );
                return list(
                        Html.element("thead", headRows),
                        Html.element("tbody", bodyRows)
                        );
            }
        }

        private boolean isHeader(DocumentElement child) {
            return tryCast(TableRow.class, child)
                    .map(TableRow::isHeader)
                    .orElse(false);
        }

        @Override
        public List<HtmlNode> visit(TableRow tableRow, Context context) {
            return list(Html.element("tr", convertChildrenToHtml(tableRow, context)));
        }

        @Override
        public List<HtmlNode> visit(TableCell tableCell, Context context) {
            String tagName = context.isHeader ? "th" : "td";
            Map<String, String> attributes = new HashMap<>();
            if (tableCell.getColspan() != 1) {
                attributes.put("colspan", Integer.toString(tableCell.getColspan()));
            }
            if (tableCell.getRowspan() != 1) {
                attributes.put("rowspan", Integer.toString(tableCell.getRowspan()));
            }
            return list(Html.element(tagName, attributes,
                    Lists.cons(Html.FORCE_WRITE, convertChildrenToHtml(tableCell, context))));
        }

        @Override
        public List<HtmlNode> visit(Hyperlink hyperlink, Context context) {
            Map<String, String> attributes = mutableMap("href", generateHref(hyperlink));
            hyperlink.getTargetFrame().ifPresent(targetFrame ->
            attributes.put("target", targetFrame)
                    );

            return list(Html.collapsibleElement("a", attributes, convertChildrenToHtml(hyperlink, context)));
        }

        private String generateHref(Hyperlink hyperlink) {
            if (hyperlink.getHref().isPresent()) {
                return hyperlink.getHref().get();
            } else if (hyperlink.getAnchor().isPresent()) {
                return "#" + generateId(hyperlink.getAnchor().get());
            } else {
                return "";
            }
        }

        @Override
        public List<HtmlNode> visit(Bookmark bookmark, Context context) {
            return list(Html.element("a", map("id", generateId(bookmark.getName())), list(Html.FORCE_WRITE)));
        }

        @Override
        public List<HtmlNode> visit(NoteReference noteReference, Context context) {
            noteReferences.add(noteReference);
            String noteAnchor = generateNoteHtmlId(noteReference.getNoteType(), noteReference.getNoteId());
            String noteReferenceAnchor = generateNoteRefHtmlId(noteReference.getNoteType(), noteReference.getNoteId());
            return list(Html.element("sup", list(
                    Html.element("a", map("href", "#" + noteAnchor, "id", noteReferenceAnchor), list(
                            Html.text("[" + noteReferences.size() + "]"))))));
        }

        @Override
        public List<HtmlNode> visit(CommentReference commentReference, Context context) {
            return styleMap.getCommentReference().orElse(HtmlPath.IGNORE).wrap(() -> {
                String commentId = commentReference.getCommentId();
                Comment comment = lookup(comments, commentId)
                        .orElseThrow(() -> new RuntimeException("Referenced comment could not be found, id: " + commentId));
                String label = "[" + comment.getAuthorInitials().orElse("") + (referencedComments.size() + 1) + "]";
                referencedComments.add(new ReferencedComment(label, comment));

                // TODO: Remove duplication with note references
                return list(Html.element(
                        "a",
                        map(
                                "href", "#" + generateReferentHtmlId("comment", commentId),
                                "id", generateReferenceHtmlId("comment", commentId)),
                        list(Html.text(label))));
            }).get();
        }

        @Override
        public List<HtmlNode> visit(Image image, Context context) {
            // TODO: custom image handlers
            // TODO: handle empty content type
            return image.getContentType()
                    .map(contentType -> {
                        try {
                            Map<String, String> attributes = new HashMap<>(imageConverter.convert(new org.zwobble.mammoth.images.Image() {
                                @Override
                                public Optional<String> getAltText() {
                                    return image.getAltText();
                                }

                                @Override
                                public String getContentType() {
                                    return contentType;
                                }

                                @Override
                                public InputStream getInputStream() throws IOException {
                                    return image.open();
                                }
                            }));
                            image.getAltText().ifPresent(altText -> attributes.put("alt", altText));
                            return list(Html.element("img", attributes));
                        } catch (IOException exception) {
                            warnings.add(exception.getMessage());
                            return Lists.<HtmlNode>list();
                        }
                    })
                    .orElse(list());
        }

        @Override
        public List<HtmlNode> visit(TableOfContents toc, Context context) {
            Supplier<List<HtmlNode>> children = () -> {
                List<HtmlNode> content = convertChildrenToHtml(toc, context);
                return preserveEmptyParagraphs ? cons(Html.FORCE_WRITE, content) : content;
            };
            return HtmlPath.element("div", Maps.map("class", tocClass)).wrap(children).get();
        }
    }

    private List<HtmlNode> convertToHtml(DocumentElement element, Context context) {
        return element.accept(new ElementConverterVisitor(), context);
    }

    private String generateNoteHtmlId(NoteType noteType, String noteId) {
        return generateReferentHtmlId(noteTypeToIdFragment(noteType), noteId);
    }

    private String generateNoteRefHtmlId(NoteType noteType, String noteId) {
        return generateReferenceHtmlId(noteTypeToIdFragment(noteType), noteId);
    }

    private String generateReferentHtmlId(String referenceType, String referenceId) {
        return generateId(referenceType + "-" + referenceId);
    }

    private String generateReferenceHtmlId(String referenceType, String referenceId) {
        return generateId(referenceType + "-ref-" + referenceId);
    }

    private String noteTypeToIdFragment(NoteType noteType) {
        switch (noteType) {
        case FOOTNOTE:
            return "footnote";
        case ENDNOTE:
            return "endnote";
        default:
            throw new UnsupportedOperationException();
        }
    }

    private String generateId(String bookmarkName) {
        return idPrefix + bookmarkName;
    }
}
