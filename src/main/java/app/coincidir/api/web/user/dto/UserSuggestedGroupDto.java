package app.coincidir.api.web.user.dto;

import java.util.List;

public class UserSuggestedGroupDto {
    public Long groupId;
    public String destination;
    public String whenLabel;
    public String status;
    public Long memberCount;
    public List<String> commonPreferences;
    public String summary;
}
