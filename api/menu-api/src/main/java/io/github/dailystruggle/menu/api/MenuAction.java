package io.github.dailystruggle.menu.api;

import java.net.URI;
import java.util.List;
import java.util.Objects;

/**
 * Declarative click or prompt action attached to a menu item or fragment (ADR-035, ADR-044,
 * ADR-045).
 *
 * <p>Renderers translate each variant to the appropriate platform click event or server-side
 * token callback without embedding raw command strings directly into client views.
 */
public sealed interface MenuAction
    permits MenuAction.RunCommand,
        MenuAction.OpenSubmenu,
        MenuAction.ChangePage,
        MenuAction.SuggestInput,
        MenuAction.PromptAnvilInput,
        MenuAction.OpenExternalUri,
        MenuAction.StageValue,
        MenuAction.UnstageValue,
        MenuAction.ApplyStagedValues,
        MenuAction.DiscardStagedValues {

  /**
   * Confirmation mode for {@link PromptAnvilInput}: {@link #RUN} executes the command immediately;
   * {@link #STAGE} records the typed value in a staging session.
   */
  enum Mode {
    RUN,
    STAGE
  }

  /**
   * Invokes a command path as the clicking viewer.
   *
   * @param args non-null, defensively copied argument tokens
   */
  record RunCommand(List<String> args) implements MenuAction {
    public RunCommand {
      Objects.requireNonNull(args, "args");
      for (int i = 0; i < args.size(); i++) {
        Objects.requireNonNull(args.get(i), "args[" + i + "]");
      }
      args = List.copyOf(args);
    }

    public RunCommand(String... args) {
      this(List.of(Objects.requireNonNull(args, "args")));
    }
  }

  /**
   * Navigates to a submenu path. Empty path represents the root menu.
   *
   * @param path non-null, defensively copied submenu path segments
   */
  record OpenSubmenu(List<String> path) implements MenuAction {
    public OpenSubmenu {
      Objects.requireNonNull(path, "path");
      for (int i = 0; i < path.size(); i++) {
        Objects.requireNonNull(path.get(i), "path[" + i + "]");
      }
      path = List.copyOf(path);
    }

    public OpenSubmenu(String... path) {
      this(List.of(Objects.requireNonNull(path, "path")));
    }
  }

  /**
   * Switches to a zero-based page index within the current menu.
   *
   * @param pageIndex target zero-based page index ({@code >= 0})
   */
  record ChangePage(int pageIndex) implements MenuAction {
    public ChangePage {
      if (pageIndex < 0) {
        throw new IllegalArgumentException("pageIndex must be >= 0, got " + pageIndex);
      }
    }
  }

  /**
   * Pre-fills the viewer's chat input bar with {@code prefix} without auto-submitting.
   *
   * @param prefix non-empty chat suggestion prefix
   */
  record SuggestInput(String prefix) implements MenuAction {
    public SuggestInput {
      Objects.requireNonNull(prefix, "prefix");
      if (prefix.isEmpty()) {
        throw new IllegalArgumentException("prefix must not be empty");
      }
    }
  }

  /**
   * Opens an anvil or text-input prompt for free-form parameter entry (ADR-045).
   *
   * @param parentPath command path leading to the target node
   * @param paramName parameter name being edited; must not be empty
   * @param prefill initial text populated in the input field
   * @param mode confirmation dispatch mode
   */
  record PromptAnvilInput(List<String> parentPath, String paramName, String prefill, Mode mode)
      implements MenuAction {
    public PromptAnvilInput {
      Objects.requireNonNull(parentPath, "parentPath");
      Objects.requireNonNull(paramName, "paramName");
      Objects.requireNonNull(prefill, "prefill");
      Objects.requireNonNull(mode, "mode");
      if (paramName.isEmpty()) {
        throw new IllegalArgumentException("paramName must not be empty");
      }
      for (int i = 0; i < parentPath.size(); i++) {
        Objects.requireNonNull(parentPath.get(i), "parentPath[" + i + "]");
      }
      parentPath = List.copyOf(parentPath);
    }

    public PromptAnvilInput(List<String> parentPath, String paramName, String prefill) {
      this(parentPath, paramName, prefill, Mode.RUN);
    }
  }

  /**
   * Opens an external {@code https} URI on the client.
   *
   * @param uri validated external URI
   */
  record OpenExternalUri(URI uri) implements MenuAction {
    public OpenExternalUri {
      Objects.requireNonNull(uri, "uri");
      String scheme = uri.getScheme();
      if (scheme == null || !scheme.equalsIgnoreCase("https") || uri.getHost() == null) {
        throw new IllegalArgumentException("External menu URI must be https with a non-null host");
      }
    }
  }

  /**
   * Stages a key-value edit in the viewer's session for the given scope.
   *
   * @param scopeName non-empty scope or file identifier
   * @param key non-empty parameter key
   * @param value staged value string
   */
  record StageValue(String scopeName, String key, String value) implements MenuAction {
    public StageValue {
      Objects.requireNonNull(scopeName, "scopeName");
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(value, "value");
      if (scopeName.isEmpty()) {
        throw new IllegalArgumentException("scopeName must not be empty");
      }
      if (key.isEmpty()) {
        throw new IllegalArgumentException("key must not be empty");
      }
    }
  }

  /**
   * Removes a previously staged key from the viewer's session for the given scope.
   *
   * @param scopeName non-empty scope or file identifier
   * @param key non-empty parameter key
   */
  record UnstageValue(String scopeName, String key) implements MenuAction {
    public UnstageValue {
      Objects.requireNonNull(scopeName, "scopeName");
      Objects.requireNonNull(key, "key");
      if (scopeName.isEmpty()) {
        throw new IllegalArgumentException("scopeName must not be empty");
      }
      if (key.isEmpty()) {
        throw new IllegalArgumentException("key must not be empty");
      }
    }
  }

  /**
   * Commits all staged values in the viewer's session for the given scope.
   *
   * @param scopeName non-empty scope or file identifier
   */
  record ApplyStagedValues(String scopeName) implements MenuAction {
    public ApplyStagedValues {
      Objects.requireNonNull(scopeName, "scopeName");
      if (scopeName.isEmpty()) {
        throw new IllegalArgumentException("scopeName must not be empty");
      }
    }
  }

  /**
   * Discards all staged values in the viewer's session for the given scope.
   *
   * @param scopeName non-empty scope or file identifier
   */
  record DiscardStagedValues(String scopeName) implements MenuAction {
    public DiscardStagedValues {
      Objects.requireNonNull(scopeName, "scopeName");
      if (scopeName.isEmpty()) {
        throw new IllegalArgumentException("scopeName must not be empty");
      }
    }
  }
}
