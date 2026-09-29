package com.smartup24.cms.instance.ms.note;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartup24.cms.core.error.ErrorCode;
import com.smartup24.cms.instance.common.error.ApiException;
import com.smartup24.cms.instance.common.security.SecurityContext;
import com.smartup24.cms.instance.ms.note.controller.MsNoteController;
import com.smartup24.cms.instance.ms.note.service.MsNoteService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Plan 10/10, item 3.1: the note endpoints refuse without a user by key, and otherwise act for that user. */
class MsNoteControllerTest {

    private final MsNoteService service = mock(MsNoteService.class);
    private final MsNoteController controller = new MsNoteController(service);

    @AfterEach
    void clear() {
        SecurityContext.clear();
    }

    @Test
    void withoutUserEveryEndpointAnswersUnauthorizedByKey() {
        SecurityContext.clear();
        var create = new MsNoteController.CreateNoteRequest("Title", "", "default", false, Map.of());
        var update = new MsNoteController.UpdateNoteRequest("Title", "", "default", null, Map.of());
        List<Runnable> calls = List.of(
                () -> controller.getNotes(null, null, null, null, null),
                () -> controller.getNote(1L),
                () -> controller.createNote(create),
                () -> controller.updateNote(1L, "\"1\"", update),
                () -> controller.togglePin(1L),
                () -> controller.setPin(1L, new MsNoteController.PinRequest(true)),
                () -> controller.deleteNote(1L));

        for (Runnable call : calls) {
            assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, error -> {
                assertThat(error.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
                assertThat(error.getMessageKey()).isEqualTo("error.note.not_authenticated");
            });
        }
    }

    @Test
    void withUserEveryEndpointActsForThatUser() {
        SecurityContext.setPrincipal(new SecurityContext.KauthPrincipal(
                7L, "user", "user@example.test", 1L, false, Set.of("notes.view"), 1L, false, 1L, null));
        var create = new MsNoteController.CreateNoteRequest("Title", "Body", "blue", true, Map.of("x", 1));
        var update = new MsNoteController.UpdateNoteRequest("New", "Text", "red", false, Map.of());

        controller.getNotes("q", 20, "c", "[]", "-title");
        controller.getNote(3L);
        when(service.createNote("Title", "Body", "blue", true, Map.of("x", 1), 7L))
                .thenReturn(new MsNoteService.NoteView(
                        11L, "Title", "Body", "blue", true, Map.of("x", 1), 7L, Instant.EPOCH, Instant.EPOCH, 1L));
        var created = controller.createNote(create);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getHeaders().getLocation()).hasToString("/api/v1/notes/11");
        controller.updateNote(3L, "\"1\"", update);
        controller.setPin(3L, new MsNoteController.PinRequest(false));
        controller.togglePin(3L);
        assertThat(controller.deleteNote(3L).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        verify(service).getNotes(7L, 20, "c", "[]", "-title", "q");
        verify(service).getNote(3L, 7L);
        verify(service).createNote("Title", "Body", "blue", true, Map.of("x", 1), 7L);
        verify(service).updateNote(3L, "New", "Text", "red", false, Map.of(), 7L, 1L);
        verify(service).setPinned(3L, 7L, false);
        verify(service).togglePinned(3L, 7L);
        verify(service).deleteNote(3L, 7L);
    }
}
