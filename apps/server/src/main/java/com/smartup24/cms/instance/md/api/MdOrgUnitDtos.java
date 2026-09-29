package com.smartup24.cms.instance.md.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.List;

/** Wire format of {@code /api/v1/iam/org-units}. */
public final class MdOrgUnitDtos {

    private MdOrgUnitDtos() {}

    public record OrgUnitView(
            Long id,
            Long parentId,
            String code,
            String name,
            String kind,
            String state,
            int orderNo,
            Instant createdAt,
            Instant modifiedAt) {}

    public record CreateOrgUnitDto(
            Long parentId, @NotBlank String code, @NotBlank String name, String kind, int orderNo) {}

    /**
     * A partial update. A class with setters, not a record: an explicit {@code "parentId": null} must differ from an
     * absent parentId, which keeps the parent. The accessors have no bean prefix so that Jackson does not see
     * {@code parentIdPresent} as a property a client could set.
     */
    public static class UpdateOrgUnitDto {
        private boolean parentIdPresent;
        private Long parentId;
        private String name;
        private String kind;
        private String state;
        private Integer orderNo;

        public void setParentId(Long parentId) {
            this.parentIdPresent = true;
            this.parentId = parentId;
        }

        public void setName(String name) {
            this.name = name;
        }

        public void setKind(String kind) {
            this.kind = kind;
        }

        public void setState(String state) {
            this.state = state;
        }

        public void setOrderNo(Integer orderNo) {
            this.orderNo = orderNo;
        }

        public boolean parentIdPresent() {
            return parentIdPresent;
        }

        public Long parentId() {
            return parentId;
        }

        public String name() {
            return name;
        }

        public String kind() {
            return kind;
        }

        public String state() {
            return state;
        }

        public Integer orderNo() {
            return orderNo;
        }
    }

    public record AssignUnitsDto(@NotNull List<@NotNull @Positive Long> orgUnitIds) {}

    public record ScopeRuleDto(@NotBlank String rule) {}

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record UserAssignments(Long userId, List<Long> orgUnitIds, Long legacyOrgUnitId) {
        public UserAssignments {
            orgUnitIds = List.copyOf(orgUnitIds);
        }
    }

    public record RoleRule(Long roleId, String rule) {}
}
