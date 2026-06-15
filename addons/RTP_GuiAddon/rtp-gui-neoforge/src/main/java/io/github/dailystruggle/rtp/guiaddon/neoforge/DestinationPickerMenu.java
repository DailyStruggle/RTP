package io.github.dailystruggle.rtp.guiaddon.neoforge;

import io.github.dailystruggle.rtp.guiaddon.common.MenuActions;
import io.github.dailystruggle.rtp.guiaddon.common.MenuEntry;
import io.github.dailystruggle.rtp.guiaddon.common.MenuModel;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;
import java.util.Locale;

/**
 * Server-side container menu that renders the RTP destination picker as a chest GUI
 * on NeoForge (Minecraft 26.x, Mojmap). Functionally identical to the Fabric
 * variant; NeoForge and Fabric share the same {@code net.minecraft} surface, but the
 * class is duplicated per loader because each compiles against its own toolchain.
 *
 * <p>Clicks in the top container submit the clicked entry's target through
 * {@link MenuActions#submit} (re-entering RTP's validated teleport path) and close the
 * screen; player-inventory slots are inert so no icon can be withdrawn.
 */
final class DestinationPickerMenu extends ChestMenu {

  private final List<MenuEntry> entries;
  private final int topSlots;

  DestinationPickerMenu(int containerId, Inventory playerInventory, MenuModel model) {
    super(menuType(model.rows()), containerId, playerInventory, buildContainer(model), model.rows());
    this.entries = model.entries();
    this.topSlots = model.rows() * 9;
  }

  private static MenuType<ChestMenu> menuType(int rows) {
    switch (rows) {
      case 1:
        return MenuType.GENERIC_9x1;
      case 2:
        return MenuType.GENERIC_9x2;
      case 3:
        return MenuType.GENERIC_9x3;
      case 4:
        return MenuType.GENERIC_9x4;
      case 5:
        return MenuType.GENERIC_9x5;
      case 6:
      default:
        return MenuType.GENERIC_9x6;
    }
  }

  private static SimpleContainer buildContainer(MenuModel model) {
    int size = model.rows() * 9;
    SimpleContainer container = new SimpleContainer(size);
    List<MenuEntry> entries = model.entries();
    for (int i = 0; i < entries.size() && i < size; i++) {
      MenuEntry entry = entries.get(i);
      container.setItem(i, icon(entry.iconName(), entry.displayName()));
    }
    return container;
  }

  private static ItemStack icon(String materialName, String displayName) {
    Item item = Items.COMPASS;
    if (materialName != null && !materialName.isEmpty()) {
      Identifier id = Identifier.tryParse(materialName.trim().toLowerCase(Locale.ROOT));
      if (id != null) {
        item = BuiltInRegistries.ITEM.getOptional(id).orElse(Items.COMPASS);
      }
    }
    ItemStack stack = new ItemStack(item);
    stack.set(DataComponents.CUSTOM_NAME, Component.literal(strip(displayName)));
    return stack;
  }

  /** Strips RTP/legacy {@code &x} and {@code §x} color codes for a plain item label. */
  private static String strip(String text) {
    if (text == null || text.isEmpty()) {
      return "";
    }
    return text.replaceAll("(?i)[&\u00a7][0-9a-fk-or]", "");
  }

  @Override
  public void clicked(int slotId, int dragType, ContainerInput clickType, Player player) {
    if (slotId >= 0 && slotId < topSlots) {
      if (slotId < entries.size()) {
        MenuActions.submit(player.getUUID(), entries.get(slotId).target());
      }
      if (player instanceof ServerPlayer serverPlayer) {
        serverPlayer.closeContainer();
      }
    }
  }

  @Override
  public boolean stillValid(Player player) {
    return true;
  }
}
