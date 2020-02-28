package saros.intellij.eventhandler.editor.editorstate;

import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileEditor.FileEditorManagerEvent;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.messages.MessageBusConnection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import saros.editor.text.LineRange;
import saros.editor.text.OldTextSelection;
import saros.intellij.editor.LocalEditorManipulator;
import saros.intellij.editor.ProjectAPI;
import saros.intellij.runtime.EDTExecutor;

/**
 * Queues viewport adjustments for editors that are not currently visible and executes the queued
 * adjustment once the corresponding editor is selected.
 */
public class ViewportAdjustmentExecutor extends AbstractLocalEditorStatusChangeHandler {
  private static final Logger log = Logger.getLogger(ViewportAdjustmentExecutor.class);

  private final LocalEditorManipulator localEditorManipulator;

  private static final Map<String, QueuedViewPortChange> queuedViewPortChanges =
      new ConcurrentHashMap<>();

  private final FileEditorManagerListener fileEditorManagerListener =
      new FileEditorManagerListener() {
        @Override
        public void selectionChanged(@NotNull FileEditorManagerEvent event) {
          assert isEnabled() : "the selection changed listener was triggered while it was disabled";

          applyQueuedViewportChanges(event.getNewFile());
        }
      };

  public ViewportAdjustmentExecutor(
      Project project, LocalEditorManipulator localEditorManipulator) {

    super(project);

    this.localEditorManipulator = localEditorManipulator;
  }

  @Override
  void registerListeners(@NotNull MessageBusConnection messageBusConnection) {
    messageBusConnection.subscribe(
        fileEditorManagerListener.FILE_EDITOR_MANAGER, fileEditorManagerListener);
  }

  /**
   * Applies any queued viewport changes to the editor representing the given virtual file. Does
   * nothing if the given file is null or if there are no queued viewport changes.
   *
   * @param virtualFile the file whose editor viewport to adjust
   */
  private void applyQueuedViewportChanges(@Nullable VirtualFile virtualFile) {
    if (virtualFile == null) {
      return;
    }

    QueuedViewPortChange queuedViewPortChange = queuedViewPortChanges.remove(virtualFile.getPath());

    if (queuedViewPortChange == null) {
      return;
    }

    LineRange range = queuedViewPortChange.getRange();
    OldTextSelection selection = queuedViewPortChange.getSelection();
    Editor queuedEditor = queuedViewPortChange.getEditor();

    Editor editor;

    if (queuedEditor != null && !queuedEditor.isDisposed()) {
      editor = queuedEditor;

    } else {
      editor = ProjectAPI.openEditor(project, virtualFile, false);

      if (editor == null) {
        log.warn(
            "Failed to apply queued viewport change as no text editor could be obtained for "
                + virtualFile);

        return;
      }
    }

    EDTExecutor.invokeAndWait(
        () -> localEditorManipulator.adjustViewport(editor, range, selection),
        ModalityState.defaultModalityState());
  }

  /**
   * Queues a viewport adjustment for the given path using the given range and selection as
   * parameters for the viewport adjustment. If an editor is given, it will be used for the viewport
   * adjustment.
   *
   * @param path the path of the editor
   * @param editor the editor to queue a viewport adjustment for
   * @param range the line range used for the viewport adjustment
   * @param selection the text selection used for the viewport adjustment
   */
  public static void queueViewPortChange(
      @NotNull String path,
      @Nullable Editor editor,
      @Nullable LineRange range,
      @Nullable OldTextSelection selection) {

    QueuedViewPortChange requestedViewportChange =
        new QueuedViewPortChange(editor, range, selection);

    queuedViewPortChanges.put(path, requestedViewportChange);
  }

  /** Data storage class for queued viewport changes. */
  private static class QueuedViewPortChange {
    private final Editor editor;
    private final LineRange range;
    private final OldTextSelection selection;

    QueuedViewPortChange(
        @Nullable Editor editor, @Nullable LineRange range, @Nullable OldTextSelection selection) {
      this.editor = editor;
      this.range = range;
      this.selection = selection;
    }

    @Nullable
    Editor getEditor() {
      return editor;
    }

    @Nullable
    LineRange getRange() {
      return range;
    }

    @Nullable
    OldTextSelection getSelection() {
      return selection;
    }
  }
}
