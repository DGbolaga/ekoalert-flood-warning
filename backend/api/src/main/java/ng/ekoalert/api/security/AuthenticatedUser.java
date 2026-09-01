package ng.ekoalert.api.security;

/**
 * Who is calling.
 *
 * @param reporterId the field identity, present for reporters and null for admins.
 *                   A report is filed by a reporter, not by a login, so this is
 *                   what the report endpoints need.
 */
public record AuthenticatedUser(Long userId, String username, String role, Long reporterId) {

    public boolean isAdmin() {
        return "ADMIN".equals(role);
    }
}
