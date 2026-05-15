package app.coincidir.api.web.user.dto;

import java.util.Map;

public class UserMatchDto {
    public Long requestId;
    public String email;

    // Core match
    public String destination;
    public String whenLabel;
    public String tripType;

    // Optional date info (used by User Panel)
    public String month;
    public Integer year;
    public String datePresetId;

    // Free text
    public String notes;

    // Extra info shown when user has no group yet
    public Map<String, Object> preferences;

    // Preferences
    public Boolean sharedRoom;
    public Boolean smokeFree;
    public Integer luggageCount;
    public Boolean travelAssistance;
    public Boolean includesTours;
    public String companionPreference;
    public Integer ageMin;
    public Integer ageMax;
    public Integer pax;
}
