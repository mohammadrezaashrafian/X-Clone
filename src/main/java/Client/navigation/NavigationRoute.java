package Client.navigation;

/**
 * Represents an application navigation destination.
 *
 * Top-level destinations are rendered inside the persistent MainLayout shell.
 * Authentication screens are handled separately by NavigationManager
 * via {@link #isTopLevel()}.
 */
public enum NavigationRoute
{
    LOGIN("/Client/fxml/Login.fxml", "X - Login"),
    REGISTER("/Client/fxml/Register.fxml", "X - Register"),
    FORGOT_PASSWORD("/Client/fxml/Forgotpassword.fxml", "X - Forgot Password"),
    VERIFY_CODE("/Client/fxml/VerifyCode.fxml", "X - Verify Code"),
    RESET_PASSWORD("/Client/fxml/ResetPassword.fxml", "X - Reset Password"),

    HOME("/Client/fxml/Timeline.fxml", "X - Home"),
    MESSAGES("/Client/fxml/Messages.fxml", "X - Messages"),
    PROFILE("/Client/fxml/Profile.fxml", "X - Profile"),
    FOLLOWING("/Client/fxml/UserList.fxml", "X - Following"),
    FOLLOWERS("/Client/fxml/UserList.fxml", "X - Followers");

    private final String fxmlPath;
    private final String title;

    NavigationRoute(String fxmlPath, String title)
    {
        this.fxmlPath = fxmlPath;
        this.title = title;
    }

    public String fxmlPath()
    {
        return fxmlPath;
    }

    public String title()
    {
        return title;
    }

    /**
     * Top-level routes are rendered inside the persistent application shell
     * (MainLayout) and participate in sidebar navigation state.
     */
    public boolean isTopLevel()
    {
        return this == HOME || this == MESSAGES || this == PROFILE
                || this == FOLLOWING || this == FOLLOWERS;
    }
}
