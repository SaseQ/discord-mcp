package dev.saseq.services;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.exceptions.HierarchyException;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction;
import net.dv8tion.jda.api.requests.restaction.CacheRestAction;
import net.dv8tion.jda.api.requests.restaction.RoleAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.awt.Color;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoleServiceTest {

    @Mock private JDA jda;
    @Mock private Guild guild;

    private RoleService service;

    @BeforeEach
    void setUp() {
        service = new RoleService(jda);
        ReflectionTestUtils.setField(service, "defaultGuildId", "");
    }

    @Test
    void resolveGuildId_fallsBackToDefault() {
        ReflectionTestUtils.setField(service, "defaultGuildId", "999");
        when(jda.getGuildById("999")).thenReturn(guild);

        Role role = mock(Role.class);
        when(guild.getRoles()).thenReturn(List.of(role));
        when(role.getName()).thenReturn("Admin");
        when(role.getId()).thenReturn("1");
        when(role.getColor()).thenReturn(null);
        when(role.getColorRaw()).thenReturn(0);
        when(role.getPosition()).thenReturn(1);
        when(role.isHoisted()).thenReturn(false);
        when(role.isMentionable()).thenReturn(false);
        when(role.getPermissionsRaw()).thenReturn(0L);

        String result = service.listRoles(null);

        assertThat(result).contains("Admin");
    }

    @Test
    void listRoles_nullGuildId_throws() {
        assertThatThrownBy(() -> service.listRoles(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("guildId cannot be null");
    }

    @Test
    void listRoles_guildNotFound_throws() {
        when(jda.getGuildById("123")).thenReturn(null);

        assertThatThrownBy(() -> service.listRoles("123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Discord server not found");
    }

    @Test
    void listRoles_emptyRoles() {
        when(jda.getGuildById("123")).thenReturn(guild);
        when(guild.getRoles()).thenReturn(List.of());

        String result = service.listRoles("123");

        assertThat(result).contains("No roles found");
    }

    @Test
    void createRole_nullName_throws() {
        assertThatThrownBy(() -> service.createRole("123", null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name cannot be null");
    }

    @Test
    void createRole_happyPath() {
        RoleAction action = mock(RoleAction.class);
        Role role = mock(Role.class);
        when(jda.getGuildById("123")).thenReturn(guild);
        when(guild.createRole()).thenReturn(action);
        when(action.setName("Moderator")).thenReturn(action);
        when(action.setColor((Color) null)).thenReturn(action);
        when(action.setHoisted(false)).thenReturn(action);
        when(action.setMentionable(false)).thenReturn(action);
        when(action.setPermissions(0L)).thenReturn(action);
        when(action.complete()).thenReturn(role);
        when(role.getName()).thenReturn("Moderator");
        when(role.getId()).thenReturn("789");
        when(role.getColorRaw()).thenReturn(0);
        when(role.isHoisted()).thenReturn(false);
        when(role.isMentionable()).thenReturn(false);
        when(role.getPermissionsRaw()).thenReturn(0L);

        String result = service.createRole("123", "Moderator", null, null, null, null);

        assertThat(result).contains("Successfully created role");
        assertThat(result).contains("Moderator");
    }

    @Test
    void createRole_insufficientPermission_throws() {
        RoleAction action = mock(RoleAction.class);
        when(jda.getGuildById("123")).thenReturn(guild);
        when(guild.createRole()).thenReturn(action);
        when(action.setName("Admin")).thenReturn(action);
        when(action.setColor((Color) null)).thenReturn(action);
        when(action.setHoisted(false)).thenReturn(action);
        when(action.setMentionable(false)).thenReturn(action);
        when(action.setPermissions(0L)).thenReturn(action);
        when(action.complete()).thenThrow(mock(InsufficientPermissionException.class));

        assertThatThrownBy(() -> service.createRole("123", "Admin", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Bot lacks permission");
    }

    @Test
    void editRole_publicRole_throws() {
        Role role = mock(Role.class);
        when(jda.getGuildById("123")).thenReturn(guild);
        when(guild.getRoleById("1")).thenReturn(role);
        when(role.isPublicRole()).thenReturn(true);

        assertThatThrownBy(() -> service.editRole("123", "1", "New", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot edit the @everyone role");
    }

    @Test
    void deleteRole_publicRole_throws() {
        Role role = mock(Role.class);
        when(jda.getGuildById("123")).thenReturn(guild);
        when(guild.getRoleById("1")).thenReturn(role);
        when(role.isPublicRole()).thenReturn(true);

        assertThatThrownBy(() -> service.deleteRole("123", "1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot delete the @everyone role");
    }

    @Test
    void deleteRole_hierarchyException_throws() {
        Role role = mock(Role.class);
        AuditableRestAction<Void> deleteAction = mock(AuditableRestAction.class);
        when(jda.getGuildById("123")).thenReturn(guild);
        when(guild.getRoleById("1")).thenReturn(role);
        when(role.isPublicRole()).thenReturn(false);
        when(role.getName()).thenReturn("Admin");
        when(role.delete()).thenReturn(deleteAction);
        when(deleteAction.complete()).thenThrow(mock(HierarchyException.class));

        assertThatThrownBy(() -> service.deleteRole("123", "1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("higher in the hierarchy");
    }

    @Test
    void assignRole_userNotFound_throws() {
        CacheRestAction<Member> retrieveAction = mock(CacheRestAction.class);
        when(jda.getGuildById("123")).thenReturn(guild);
        when(guild.retrieveMemberById("user1")).thenReturn(retrieveAction);
        when(retrieveAction.complete()).thenThrow(mock(net.dv8tion.jda.api.exceptions.ErrorResponseException.class));

        assertThatThrownBy(() -> service.assignRole("123", "user1", "role1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("User not found");
    }
}
