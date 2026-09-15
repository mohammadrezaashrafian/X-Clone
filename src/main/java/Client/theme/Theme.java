package Client.theme;

/**
 * Supported application themes.
 *
 * Each theme maps to a stylesheet in /Client/css/themes/ that redefines
 * the semantic color tokens (-ds-*) consumed by the design system.
 */
public enum Theme
{
    DARK("/Client/css/themes/dark.css"),
    LIGHT("/Client/css/themes/light.css");

    private final String stylesheet;

    Theme(String stylesheet)
    {
        this.stylesheet = stylesheet;
    }

    /** Classpath URL of the stylesheet that defines this theme's tokens. */
    public String stylesheet()
    {
        return stylesheet;
    }

    /** Lowercase name used for preference persistence. */
    public String storageKey()
    {
        return name().toLowerCase();
    }

    /**
     * Parses a stored preference value, falling back to DARK for
     * unknown or missing values.
     */
    public static Theme fromStorageKey(String value)
    {
        if (value != null)
        {
            for (Theme theme : values())
            {
                if (theme.storageKey().equalsIgnoreCase(value.trim()))
                {
                    return theme;
                }
            }
        }
        return DARK;
    }
}
