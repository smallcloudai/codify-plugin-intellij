package com.smallcloud.refactai.modes.completion.renderer

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.ui.JBColor
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Font
import java.awt.Font.ITALIC
import java.awt.font.TextAttribute

object RenderHelper {
    fun getFont(editor: Editor, deprecated: Boolean): Font {
        val font = UIUtil.getFontWithFallback(editor.colorsScheme.getFont(EditorFontType.ITALIC)).deriveFont(ITALIC);
        if (!deprecated) {
            return font
        }
        val attributes: MutableMap<TextAttribute, Any?> = HashMap(font.attributes)
        attributes[TextAttribute.STRIKETHROUGH] = TextAttribute.STRIKETHROUGH_ON
        return Font(attributes)
    }

    fun getFont(editor: Editor, deprecated: Boolean, text :String): Font {
        val font = UIUtil.getFontWithFallbackIfNeeded(editor.colorsScheme.getFont(EditorFontType.ITALIC),text).deriveFont(
            ITALIC
        );
        if (!deprecated) {
            return font
        }
        val attributes: MutableMap<TextAttribute, Any?> = HashMap(font.attributes)
        attributes[TextAttribute.STRIKETHROUGH] = TextAttribute.STRIKETHROUGH_ON
        return Font(attributes)
    }

    val color: Color
        get() {
            return JBColor.GRAY
        }
}
