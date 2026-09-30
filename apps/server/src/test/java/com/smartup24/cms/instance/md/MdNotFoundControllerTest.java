package com.smartup24.cms.instance.md;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.smartup24.cms.instance.config.error.GlobalExceptionHandler;
import com.smartup24.cms.instance.config.error.PackagedProblemMessages;
import com.smartup24.cms.instance.md.controller.ModuleRegistryController;
import com.smartup24.cms.instance.md.controller.NavigationItemController;
import com.smartup24.cms.instance.md.service.ModuleRegistryService;
import com.smartup24.cms.instance.md.service.NavigationItemService;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Plan 10/10, item 3.1: a missing record answers problem details with a catalog key, never an empty 404. */
class MdNotFoundControllerTest {

    private final NavigationItemService navigation = mock(NavigationItemService.class);
    private final ModuleRegistryService modules = mock(ModuleRegistryService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(
                    new NavigationItemController(navigation), new ModuleRegistryController(modules))
            .setControllerAdvice(new GlobalExceptionHandler(PackagedProblemMessages.russian()))
            .build();

    @Test
    @DisplayName("3.1: a missing navigation item by id answers not_found with its key")
    void navigationItemById() throws Exception {
        when(navigation.getItemById(7L)).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/navigation/items/7"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.code").value("not_found"))
                .andExpect(jsonPath("$.messageKey").value("error.md.navigation_item_not_found"))
                .andExpect(jsonPath("$.params.id").value(7));
    }

    @Test
    @DisplayName("3.1: a missing navigation item by code answers not_found with its key")
    void navigationItemByCode() throws Exception {
        when(navigation.getVisibleItemByCode("reports")).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/navigation/items/by-code/reports"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.messageKey").value("error.md.navigation_item_code_not_found"))
                .andExpect(jsonPath("$.detail").value("Пункт навигации не найден: reports"));
    }

    @Test
    @DisplayName("3.1: a missing module answers not_found with its key")
    void module() throws Exception {
        when(modules.getModule("nope")).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/modules/nope"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType("application/problem+json"))
                .andExpect(jsonPath("$.messageKey").value("error.md.module_not_found"))
                .andExpect(jsonPath("$.params.code").value("nope"));
    }
}
