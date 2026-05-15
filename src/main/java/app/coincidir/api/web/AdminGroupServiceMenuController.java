package app.coincidir.api.web;

import app.coincidir.api.service.GroupServiceMenuService;
import app.coincidir.api.web.dto.CreateGroupServiceMenuItemRequest;
import app.coincidir.api.web.dto.GroupServiceMenuDto;
import app.coincidir.api.web.dto.GroupServiceMenuItemDto;
import app.coincidir.api.web.dto.UpdateGroupServiceMenuOrderRequest;
import app.coincidir.api.web.dto.UpdateGroupServiceMenuQuotesRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/groups/{groupId}/service-menu")
@RequiredArgsConstructor
public class AdminGroupServiceMenuController {

    private final GroupServiceMenuService menuService;

    @GetMapping
    public GroupServiceMenuDto getMenu(@PathVariable Long groupId) {
        return menuService.getMenu(groupId);
    }

    @PostMapping("/items")
    public GroupServiceMenuItemDto addItem(
            @PathVariable Long groupId,
            @RequestBody @Valid CreateGroupServiceMenuItemRequest body
    ) {
        return menuService.addMenuItem(groupId, body);
    }

    @DeleteMapping("/items/{menuItemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteItem(
            @PathVariable Long groupId,
            @PathVariable Long menuItemId
    ) {
        menuService.deleteMenuItem(groupId, menuItemId);
    }

    @PutMapping("/order")
    public GroupServiceMenuDto updateOrder(
            @PathVariable Long groupId,
            @RequestBody @Valid UpdateGroupServiceMenuOrderRequest body
    ) {
        return menuService.updateOrder(groupId, body);
    }

    @PutMapping("/quotes")
    public GroupServiceMenuDto updateQuotes(
            @PathVariable Long groupId,
            @RequestBody UpdateGroupServiceMenuQuotesRequest body
    ) {
        return menuService.updateQuotes(groupId, body);
    }
}
