package org.beeender.comradeneovim.core

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.*
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectLocator
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import org.beeender.neovim.BufLinesEvent
import java.io.Closeable

class SyncedBuffer(val id: Int, val path: String) : Closeable {

    private val orgVirtualFile: VirtualFile
    private val orgDocument: Document
    private val orgPsiFile: PsiFile
    private val inMemPsiFile: PsiFile
    private val inMemDocument: Document
    private val log = Logger.getInstance(SyncedBuffer::class.java)
    val editor: Editor
    val project: Project
    var changedTick: Int = -1
        private set
    val text get() = inMemDocument.text

    init {
        val tmpVirtualFile = findVirtualFile(path)
        project = ProjectLocator.getInstance().guessProjectForFile(tmpVirtualFile) ?: throw IllegalStateException()
        /*
        val editorMan = FileEditorManager.getInstance(project!!)
        val openedVirtualFile = editorMan.openFiles.find {
            v -> Files.isSameFile(Paths.get(v.path), Paths.get(name))
        }
        if (openedVirtualFile != null) {
            orgVirtualFile = openedVirtualFile
        }
        else {
            throw IllegalStateException()
        }
        */
        orgVirtualFile = tmpVirtualFile!!

        orgPsiFile = PsiManager.getInstance(project).findFile(orgVirtualFile) ?: throw IllegalStateException()
        orgDocument = FileDocumentManager.getInstance().getDocument(orgVirtualFile) ?: throw IllegalStateException()

        val psiFactory = PsiFileFactory.getInstance(project)
        val pf = psiFactory.createFileFromText(orgPsiFile.name, orgPsiFile.language, "")
        val doc = PsiDocumentManager.getInstance(project).getDocument(pf) ?: throw IllegalStateException()

        inMemPsiFile = pf
        inMemDocument = doc
        editor = EditorFactory.getInstance().createEditor(inMemDocument)
    }

    fun getCaretOnPosition(row: Int, col: Int) : Caret {
        val caret = editor.caretModel.currentCaret
        caret.moveToLogicalPosition(LogicalPosition(row, col))
        return caret
    }

    private fun setText(text: CharSequence) {
        ApplicationManager.getApplication().runWriteAction {
            inMemDocument.setText(text)
        }
    }

    private fun replaceText(startOffset: Int, endOffset: Int, text: CharSequence) {
        log.info("replaceText start: $startOffset, end: $endOffset, with '$text'")
        ApplicationManager.getApplication().runWriteAction {
            WriteCommandAction.writeCommandAction(project)
                    .run<Throwable> {
                        inMemDocument.replaceString(startOffset, endOffset, text)
                        log.info("replaceText")
                    }
        }
    }

    private fun insertText(offset: Int, text: CharSequence) {
        ApplicationManager.getApplication().runWriteAction {
            WriteCommandAction.writeCommandAction(project)
                    .run<Throwable> {
                        inMemDocument.insertString(offset, text)
                        log.info("insertText")
                    }
        }
    }

    private fun deleteText(start: Int, end: Int) {
        ApplicationManager.getApplication().runWriteAction {
            WriteCommandAction.writeCommandAction(project)
                    .run<Throwable> {
                        inMemDocument.deleteString(start, end)
                        log.info("deleteText")
                    }
        }
    }

    internal fun onBufferChanged(bufLinesEvent: BufLinesEvent) {
        log.info("BufferLineEventHandled start changedTick: ${bufLinesEvent.changedTick}")
        if (bufLinesEvent.hasMore) {
            TODO("Handle more")
        }


        val lineData = bufLinesEvent.lineData
        val lastLine = bufLinesEvent.lastLine
        val firstLine = bufLinesEvent.firstLine

        /*
        if (bufLinesEvent.changedTick < changedTick && firstLine != 0 && lastLine != -1) {
            TODO("Handle mismatched buffer")
        }
        */

        val stringBuilder = StringBuilder()
        lineData.forEachIndexed { index, s ->
            stringBuilder.append(s)
            if (index < lineData.size - 1) {
                stringBuilder.append('\n')
            }
        }
        if (lastLine == -1) {
            setText(stringBuilder)
        }
        else
        {
            val curLineCount = inMemDocument.lineCount
            // start should include the previous EOL
            val start = when (firstLine) {
                0 -> 0
                curLineCount -> inMemDocument.getLineEndOffset(firstLine - 1)
                else -> inMemDocument.getLineStartOffset(firstLine) - 1
            }
            if (firstLine == lastLine) {
                if (start != 0) {
                    stringBuilder.insert(0, '\n')
                }
                else {
                    stringBuilder.append('\n')
                }
                // Insertion
                insertText(start,
                        if (firstLine == curLineCount) stringBuilder
                        else stringBuilder)
            }
            else
            {
                // Replace the whole end line including EOL
                val end = when {
                    lastLine > curLineCount -> 0
                    lastLine == curLineCount -> inMemDocument.getLineEndOffset(lastLine - 1)
                    else -> inMemDocument.getLineEndOffset(lastLine - 1) + 1
                }
                if (stringBuilder.isEmpty()) {
                    deleteText(start,
                            if (lastLine >= curLineCount) end
                            else end - 1)
                }
                else
                {
                    if (firstLine != 0) {
                        stringBuilder.insert(0, '\n')
                    }
                    replaceText(start, end,
                            if (lastLine >= curLineCount) stringBuilder
                            else stringBuilder.append('\n'))
                }
            }
        }
        changedTick = bufLinesEvent.changedTick
        log.info("BufferLineEventHandled changedTick: $changedTick")
    }

    override fun close() {
        EditorFactory.getInstance().releaseEditor(editor)
    }
}

private fun findVirtualFile(name: String): VirtualFile? {
    val url = when(name.contains(':')){
        true -> name
        false -> VfsUtil.pathToUrl(name)
    }
    return ApplicationManager.getApplication().runWriteAction(Computable {
        return@Computable VirtualFileManager.getInstance().refreshAndFindFileByUrl(url)
    })
}