package io.forgetdm.security;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/security")
public class SecurityAdminController {
    private final AccessControlService access;

    public SecurityAdminController(AccessControlService access) {
        this.access = access;
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        return Map.of(
                "users", access.users(),
                "groups", access.groups(),
                "roles", RoleDefinition.ALL);
    }

    @GetMapping("/roles")
    public List<RoleDefinition> roles() {
        return RoleDefinition.ALL;
    }

    @GetMapping("/users")
    public List<AccessControlService.UserView> users() {
        return access.users();
    }

    @PostMapping("/users")
    public AccessControlService.UserView createUser(@RequestBody AccessControlService.UserRequest req) {
        return access.createUser(req);
    }

    @PutMapping("/users/{id}")
    public AccessControlService.UserView updateUser(@PathVariable long id, @RequestBody AccessControlService.UserRequest req) {
        return access.updateUser(id, req);
    }

    @DeleteMapping("/users/{id}")
    public void deleteUser(@PathVariable long id) {
        access.deleteUser(id);
    }

    @GetMapping("/groups")
    public List<AccessControlService.GroupView> groups() {
        return access.groups();
    }

    @PostMapping("/groups")
    public AccessControlService.GroupView createGroup(@RequestBody AccessControlService.GroupRequest req) {
        return access.createGroup(req);
    }

    @PutMapping("/groups/{id}")
    public AccessControlService.GroupView updateGroup(@PathVariable long id, @RequestBody AccessControlService.GroupRequest req) {
        return access.updateGroup(id, req);
    }

    @DeleteMapping("/groups/{id}")
    public void deleteGroup(@PathVariable long id) {
        access.deleteGroup(id);
    }
}
