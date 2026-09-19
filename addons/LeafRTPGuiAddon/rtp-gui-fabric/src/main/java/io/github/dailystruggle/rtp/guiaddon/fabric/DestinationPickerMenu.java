package io.github.dailystruggle.rtp.guiaddon.fabric;

import io.github.dailystruggle.rtp.guiaddon.common.MenuActions;
import io.github.dailystruggle.rtp.guiaddon.common.MenuEntry;
import io.github.dailystruggle.rtp.guiaddon.common.MenuIcons;
import io.github.dailystruggle.rtp.guiaddon.common.MenuLayout;
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
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Server-side container menu that renders the RTP destination picker as a chest GUI
 * on Fabric (Minecraft 26.x, Mojmap/deobfuscated).
 *
 * <p>This is the Fabric equivalent of the Bukkit {@code DestinationPickerGui}: it
 * presents the platform-neutral {@link MenuModel} entries as clickable items in a
 * read-only chest inventory. Both renderers share the same {@link MenuLayout} (slot
 * placement: a centred, border-framed block) and {@link MenuIcons} (label + lore),
 * so the menu looks identical on every platform - the icons no longer cling to slot
 * 0. Clicks in the top container call {@link MenuActions#submit} for the clicked
 * entry's target and close the screen; the engine then runs RTP's fully validated
 * teleport path. The player's own inventory slots are inert, so this is purely a
 * picker.
 */
final class DestinationPickerMenu extends ChestMenu {

  private final Map<Integer, MenuEntry> slotEntries;
  private final int topSlots;

  DestinationPickerMenu(int containerId, Inventory playerInventory, MenuModel model, MenuLayout layout) {
    super(menuType(layout.rows()), containerId, playerInventory, buildContainer(model, layout), layout.rows());
    this.slotEntries = layout.slotEntries();
    this.topSlots = layout.size();
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

  private static SimpleContainer buildContainer(MenuModel model, MenuLayout layout) {
    SimpleContainer container = new SimpleContainer(layout.size());

    for (Map.Entry<Integer, MenuEntry> placed : layout.slotEntries().entrySet()) {
      MenuEntry entry = placed.getValue();
      container.setItem(placed.getKey(), icon(entry.iconName(), entry.displayName(),
          MenuIcons.entryLore(entry)));
    }

    if (layout.hasDashboard()) {
      container.setItem(layout.dashboardSlot(),
          icon(model.dashboardIconName(), MenuIcons.dashboardTitle(), MenuIcons.dashboardLore(model)));
    }

    applyFiller(container, model);
    return container;
  }

  /** Fills every still-empty slot with the configured filler item (blank label). */
  private static void applyFiller(SimpleContainer container, MenuModel model) {
    String fillerName = model.fillerName();
    if (fillerName == null || fillerName.trim().isEmpty()) {
      return;
    }
    Item fillerItem = resolveItem(fillerName);
    if (fillerItem == Items.AIR) {
      return;
    }
    for (int i = 0; i < container.getContainerSize(); i++) {
      if (container.getItem(i).isEmpty()) {
        ItemStack pane = new ItemStack(fillerItem);
        pane.set(DataComponents.CUSTOM_NAME, Component.literal(" "));
        container.setItem(i, pane);
      }
    }
  }

  private static ItemStack icon(String materialName, String displayName, List<String> lore) {
    ItemStack stack = new ItemStack(resolveItem(materialName));
    stack.set(DataComponents.CUSTOM_NAME, Component.literal(strip(displayName)));
    if (lore != null && !lore.isEmpty()) {
      List<Component> lines = new ArrayList<>(lore.size());
      for (String line : lore) {
        lines.add(Component.literal(strip(line)));
      }
      stack.set(DataComponents.LORE, new ItemLore(lines));
    }
    return stack;
  }

  private static Item resolveItem(String materialName) {
    if (materialName == null || materialName.isEmpty()) {
      return Items.COMPASS;
    }
    Identifier id = Identifier.tryParse(materialName.trim().toLowerCase(Locale.ROOT));
    if (id == null) {
      return Items.COMPASS;
    }
    return BuiltInRegistries.ITEM.getOptional(id).orElse(Items.COMPASS);
  }

  /** Strips RTP/legacy {@code &x} and {@code §x} color codes for a plain label. */
  private static String strip(String text) {
    if (text == null || text.isEmpty()) {
      return "";
    }
    return text.replaceAll("(?i)[&\u00a7][0-9a-fk-or]", "");
  }

  @Override
  public void clicked(int slotId, int dragType, ContainerInput clickType, Player player) {
    // Only top-container clicks select a destination; everything else is inert so
    // the picker can never be used to move or withdraw the icon items.
    if (slotId >= 0 && slotId < topSlots) {
      MenuEntry entry = slotEntries.get(slotId);
      if (entry != null) {
        MenuActions.submit(player.getUUID(), entry.target());
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
