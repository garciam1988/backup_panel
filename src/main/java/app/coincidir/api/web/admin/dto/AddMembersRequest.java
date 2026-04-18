// AddMembersRequest.java
package app.coincidir.api.web.admin.dto;

import java.util.List;

public record AddMembersRequest(
        java.util.List<Long> requestIds
) {}
