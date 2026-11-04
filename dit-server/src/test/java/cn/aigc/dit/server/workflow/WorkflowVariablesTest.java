package cn.aigc.dit.server.workflow;

import cn.aigc.dit.common.exception.BizException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowVariablesTest {

    @Test
    void localVariablesOverrideUpstreamAndMissingFails() {
        var scopes = List.of(
                new WorkflowVariables.Scope(0, "local", Map.of("角色", "阿青")),
                new WorkflowVariables.Scope(1, "upstream", Map.of("角色", "路人", "地点", "雨巷")));

        var resolved = WorkflowVariables.resolve("{{角色}}在{{地点}}", scopes);
        assertEquals("阿青在雨巷", resolved.value());
        assertEquals(Map.of("角色", "阿青", "地点", "雨巷"), resolved.values());

        BizException missing = assertThrows(BizException.class,
                () -> WorkflowVariables.resolve("{{天气}}", scopes));
        assertTrue(missing.getMessage().contains("天气"));
    }

    @Test
    void samePriorityDistinctValuesFailButRepeatedPlaceholdersResolve() {
        var scopes = List.of(
                new WorkflowVariables.Scope(1, "left", Map.of("角色", "甲")),
                new WorkflowVariables.Scope(1, "right", Map.of("角色", "乙")));

        BizException conflict = assertThrows(BizException.class,
                () -> WorkflowVariables.resolve("{{ 角色 }}", scopes));
        assertTrue(conflict.getMessage().contains("角色"));

        assertEquals("甲和甲", WorkflowVariables.resolve("{{角色}}和{{ 角色 }}",
                List.of(new WorkflowVariables.Scope(0, "local", Map.of("角色", "甲")))).value());
    }

    @Test
    void laterHigherPriorityValueSuppressesLowerPriorityConflict() {
        var scopes = List.of(
                new WorkflowVariables.Scope(1, "upstream:a", Map.of("角色", "甲")),
                new WorkflowVariables.Scope(1, "upstream:b", Map.of("角色", "乙")),
                new WorkflowVariables.Scope(0, "local", Map.of("角色", "阿青")));

        assertEquals("阿青", WorkflowVariables.resolve("{{角色}}", scopes).value());
    }
}
