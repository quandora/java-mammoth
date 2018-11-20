package org.zwobble.mammoth.internal.documents;

import java.util.List;

/**
 * Map a Structured Document Tag containing a Table of contents
 * <w:sdt>
 *    <w:sdtPr>
 *           <w:id w:val="458904407" />
 *             <w:docPartObj>
 *                <w:docPartType w:val="Table of Contents" />
 *                <w:docPartUnique />
 *      </w:docPartObj>
 *    </w:sdtPr>
 *    <w:sdtContent>
 *    ...
 *    </w:sdtContent>
 * </w:sdt>
 */
public class TableOfContents implements DocumentElement, HasChildren {
    private final List<DocumentElement> children;

    public TableOfContents(
            List<DocumentElement> children
            ) {
        this.children = children;
    }

    @Override
    public List<DocumentElement> getChildren() {
        return children;
    }

    @Override
    public <T, U> T accept(DocumentElementVisitor<T, U> visitor, U context) {
        return visitor.visit(this, context);
    }
}
