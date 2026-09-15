package Client.controllers;

import Client.ClientApplicationContext;
import javafx.fxml.FXML;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;

import java.util.function.Consumer;

/**
 * Reusable search box for the utility panel.
 *
 * The component owns the input only; the host supplies what a submitted query
 * should do (and where results are rendered), so the same box can be reused in
 * other screens without dragging the search logic along.
 */
public class SearchBoxController
{
    @FXML
    private HBox searchBox;

    @FXML
    private TextField searchInput;

    private final ClientApplicationContext context;

    private Consumer<String> onSearch;

    public SearchBoxController(ClientApplicationContext context)
    {
        this.context = context;
    }

    @FXML
    private void initialize()
    {
        searchInput.setOnAction(event -> submit());
    }

    /** Registers the handler invoked with the trimmed query on ENTER. */
    public void setOnSearch(Consumer<String> onSearch)
    {
        this.onSearch = onSearch;
    }

    /** Moves keyboard focus into the input (used by the Ctrl+K shortcut). */
    public void focusInput()
    {
        if (searchInput != null)
        {
            searchInput.requestFocus();
        }
    }

    /** Clears the query and any rendered results. */
    public void clear()
    {
        if (searchInput != null)
        {
            searchInput.clear();
        }

        if (onSearch != null)
        {
            onSearch.accept("");
        }
    }

    private void submit()
    {
        if (onSearch == null)
        {
            return;
        }

        String query = searchInput.getText();
        onSearch.accept(query == null ? "" : query.trim());
    }
}
