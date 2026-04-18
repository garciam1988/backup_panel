package app.coincidir.api.web.user;

import app.coincidir.api.web.user.dto.SuggestedGroupDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;

/**
 * Alias endpoints for compatibility with different User Panel frontend versions.
 * Delegates to {@link UserGroupController}.
 */
@RestController
@RequiredArgsConstructor
public class UserGroupsAliasController {

    private final UserGroupController userGroupController;

    @GetMapping("/api/user/group/suggestions")
    public ResponseEntity<List<SuggestedGroupDto>> suggestions(Principal principal) {
        return userGroupController.suggested(principal);
    }

    @GetMapping("/api/user/groups/suggested")
    public ResponseEntity<List<SuggestedGroupDto>> groupsSuggested(Principal principal) {
        return userGroupController.suggested(principal);
    }

    @GetMapping("/api/groups/suggested")
    public ResponseEntity<List<SuggestedGroupDto>> publicSuggested(Principal principal) {
        return userGroupController.suggested(principal);
    }

    @PostMapping("/api/user/groups/{groupId}/apply")
    public ResponseEntity<Void> applyUserGroups(@PathVariable Long groupId, Principal principal) {
        return userGroupController.apply(groupId, principal);
    }

    @PostMapping("/api/groups/{groupId}/apply")
    public ResponseEntity<Void> applyGroups(@PathVariable Long groupId, Principal principal) {
        return userGroupController.apply(groupId, principal);
    }
}
