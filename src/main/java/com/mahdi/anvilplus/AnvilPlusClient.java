package com.mahdi.anvilplus;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.AnvilScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.screen.AnvilScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * Anvil Plus - Minecraft 1.21.11
 *
 * Client-side automation for Netherite Boots only.
 * It uses normal Anvil screen-handler clicks, so the server remains
 * authoritative about the actual enchantment result and XP cost.
 */
public class AnvilPlusClient implements ClientModInitializer {

    private static final String MOD_ID = "anvil_plus";

    // 6 ticks = 300 ms.
    private static final int ACTION_DELAY_TICKS = 6;
    private static final int OPEN_DELAY_TICKS = 8;

    private static final KeyBinding.Category CATEGORY =
            KeyBinding.Category.create(
                    Identifier.of(MOD_ID, "controls")
            );

    private static KeyBinding toggleKey;

    private static boolean enabled = true;

    private static AnvilScreenHandler lastHandler;
    private static int delay;

    private static Stage stage = Stage.IDLE;

    private static ItemStack selectedBoot = ItemStack.EMPTY;
    private static ItemStack selectedBook = ItemStack.EMPTY;

    private enum Stage {
        IDLE,
        WAITING_FOR_BOOT,
        WAITING_FOR_BOOK,
        WAITING_FOR_OUTPUT,
        WAITING_FOR_CLEAR
    }

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(
                new KeyBinding(
                        "key.anvil_plus.toggle",
                        InputUtil.Type.KEYSYM,
                        GLFW.GLFW_KEY_P,
                        CATEGORY
                )
        );

        loadConfig();

        ClientTickEvents.END_CLIENT_TICK.register(
                AnvilPlusClient::tick
        );
    }

    private static void tick(MinecraftClient client) {
        while (toggleKey.wasPressed()) {
            enabled = !enabled;
            resetState();
            saveConfig();

            if (enabled) {
                actionBar(client, "Anvil Plus: ON", 0x55FF55);
            } else {
                actionBar(client, "Anvil Plus: OFF", 0xFF5555);
            }
        }

        if (!enabled || client.player == null || client.interactionManager == null) {
            return;
        }

        if (!(client.currentScreen instanceof AnvilScreen screen)) {
            resetState();
            return;
        }

        AnvilScreenHandler handler = screen.getScreenHandler();

        if (handler != lastHandler) {
            lastHandler = handler;
            delay = OPEN_DELAY_TICKS;
            stage = Stage.IDLE;
            selectedBoot = ItemStack.EMPTY;
            selectedBook = ItemStack.EMPTY;
        }

        if (delay > 0) {
            delay--;
            return;
        }

        switch (stage) {
            case IDLE -> startNext(client, handler);
            case WAITING_FOR_BOOT -> putBook(client, handler);
            case WAITING_FOR_BOOK -> waitForOutput(client, handler);
            case WAITING_FOR_OUTPUT -> takeOutput(client, handler);
            case WAITING_FOR_CLEAR -> waitForClear(handler);
        }
    }

    private static void startNext(
            MinecraftClient client,
            AnvilScreenHandler handler
    ) {
        if (!isInputEmpty(handler)) {
            stopWithError(client, "Anvil Plus: Anvil slots are not empty", 0xFF5555);
            return;
        }

        int bootSlot = findNextBoot(client);
        if (bootSlot < 0) {
            actionBar(client, "Anvil Plus: No Netherite Boots left", 0x55FF55);
            delay = 20;
            return;
        }

        ItemStack boot = client.player.getInventory().getStack(bootSlot);

        int bookSlot = findUsefulBook(client, boot);
        if (bookSlot < 0) {
            actionBar(client, "Anvil Plus: No useful book for this boot", 0xFFAA00);
            delay = 20;
            return;
        }

        selectedBoot = boot.copy();
        selectedBook = client.player.getInventory().getStack(bookSlot).copy();

        // Put the Netherite Boots into Anvil slot 1.
        clickInventorySlot(
                client,
                handler,
                bootSlot
        );

        stage = Stage.WAITING_FOR_BOOT;
        delay = ACTION_DELAY_TICKS;
    }

    private static void putBook(
            MinecraftClient client,
            AnvilScreenHandler handler
    ) {
        ItemStack input = handler
                .getSlot(AnvilScreenHandler.INPUT_1_ID)
                .getStack();

        if (input.isEmpty()) {
            delay = 1;
            return;
        }

        int bookSlot = findExactBook(client, selectedBook);
        if (bookSlot < 0) {
            stopWithError(client, "Anvil Plus: Enchanted Book not found", 0xFF5555);
            return;
        }

        clickInventorySlot(
                client,
                handler,
                bookSlot
        );

        stage = Stage.WAITING_FOR_BOOK;
        delay = ACTION_DELAY_TICKS;
    }

    private static void waitForOutput(
            MinecraftClient client,
            AnvilScreenHandler handler
    ) {
        ItemStack input1 = handler
                .getSlot(AnvilScreenHandler.INPUT_1_ID)
                .getStack();
        ItemStack input2 = handler
                .getSlot(AnvilScreenHandler.INPUT_2_ID)
                .getStack();

        if (input1.isEmpty() || input2.isEmpty()) {
            // Give the server a little time to synchronize the second click.
            delay = 1;
            return;
        }

        stage = Stage.WAITING_FOR_OUTPUT;
        delay = ACTION_DELAY_TICKS;
    }

    private static void takeOutput(
            MinecraftClient client,
            AnvilScreenHandler handler
    ) {
        ItemStack output = handler
                .getSlot(AnvilScreenHandler.OUTPUT_ID)
                .getStack();

        if (output.isEmpty()) {
            int cost = handler.getLevelCost();

            if (cost > 39) {
                stopWithError(client, "Anvil Plus: Too Expensive!", 0xFF5555);
            } else if (cost > client.player.experienceLevel) {
                stopWithError(client, "Anvil Plus: Not Enough XP", 0xFF5555);
            } else {
                stopWithError(client, "Anvil Plus: Incompatible or invalid book", 0xFFAA00);
            }
            return;
        }

        int cost = handler.getLevelCost();

        if (cost > 39) {
            stopWithError(client, "Anvil Plus: Too Expensive!", 0xFF5555);
            return;
        }

        if (cost > client.player.experienceLevel) {
            stopWithError(client, "Anvil Plus: Not Enough XP", 0xFF5555);
            return;
        }

        // Take the actual Anvil result. This is a normal server-authoritative click.
        client.interactionManager.clickSlot(
                handler.syncId,
                AnvilScreenHandler.OUTPUT_ID,
                0,
                SlotActionType.QUICK_MOVE,
                client.player
        );

        stage = Stage.WAITING_FOR_CLEAR;
        delay = ACTION_DELAY_TICKS;

        actionBar(client, "Anvil Plus: Enchanted", 0x55FF55);
    }

    private static void waitForClear(AnvilScreenHandler handler) {
        boolean input1Empty = handler
                .getSlot(AnvilScreenHandler.INPUT_1_ID)
                .getStack()
                .isEmpty();
        boolean input2Empty = handler
                .getSlot(AnvilScreenHandler.INPUT_2_ID)
                .getStack()
                .isEmpty();
        boolean outputEmpty = handler
                .getSlot(AnvilScreenHandler.OUTPUT_ID)
                .getStack()
                .isEmpty();

        if (!input1Empty || !input2Empty || !outputEmpty) {
            delay = 1;
            return;
        }

        selectedBoot = ItemStack.EMPTY;
        selectedBook = ItemStack.EMPTY;
        stage = Stage.IDLE;
        delay = ACTION_DELAY_TICKS;
    }

    private static int findNextBoot(MinecraftClient client) {
        for (int i = 0; i < client.player.getInventory().size(); i++) {
            ItemStack stack = client.player.getInventory().getStack(i);

            if (!stack.isEmpty() && stack.isOf(Items.NETHERITE_BOOTS)) {
                return i;
            }
        }

        return -1;
    }

    private static int findExactBook(
            MinecraftClient client,
            ItemStack wanted
    ) {
        for (int i = 0; i < client.player.getInventory().size(); i++) {
            ItemStack stack = client.player.getInventory().getStack(i);

            if (!stack.isEmpty()
                    && stack.isOf(Items.ENCHANTED_BOOK)
                    && ItemStack.areItemsAndComponentsEqual(stack, wanted)) {
                return i;
            }
        }

        return -1;
    }

    private static int findUsefulBook(
            MinecraftClient client,
            ItemStack boot
    ) {
        for (int i = 0; i < client.player.getInventory().size(); i++) {
            ItemStack book = client.player.getInventory().getStack(i);

            if (book.isEmpty() || !book.isOf(Items.ENCHANTED_BOOK)) {
                continue;
            }

            if (hasUsefulAllowedEnchantment(boot, book)) {
                return i;
            }
        }

        return -1;
    }

    /**
     * Only the seven enchantments requested by the user are considered:
     * Unbreaking III, Feather Falling IV, Blast Protection IV, Thorns III,
     * Depth Strider III, Mending and Soul Speed III.
     *
     * A multi-enchantment book is valid when at least one of those enchantments
     * would add a new level to the boots. Existing equal/higher enchantments
     * are ignored, so a book is not selected just to repeat an enchantment.
     */
    private static boolean hasUsefulAllowedEnchantment(
            ItemStack boot,
            ItemStack book
    ) {
        var bootEnchants = EnchantmentHelper.getEnchantments(boot);
        var bookEnchants = EnchantmentHelper.getEnchantments(book);

        for (RegistryEntry<Enchantment> entry : bookEnchants.getEnchantments()) {
            Identifier id = Registries.ENCHANTMENT.getId(entry.value());

            if (!isAllowed(id)) {
                continue;
            }

            int bookLevel = bookEnchants.getLevel(entry);
            int bootLevel = bootEnchants.getLevel(entry);

            // Same or higher level is already on the boots: do not use the book for this.
            if (bootLevel >= bookLevel) {
                continue;
            }

            // Let vanilla/Anvil decide the final compatibility, but don't select
            // enchantments that the boots cannot support at all.
            if (!entry.value().isSupportedItem(boot)) {
                continue;
            }

            return true;
        }

        return false;
    }

    private static boolean isAllowed(Identifier id) {
        if (id == null) {
            return false;
        }

        return id.equals(Identifier.of("minecraft", "unbreaking"))
                || id.equals(Identifier.of("minecraft", "feather_falling"))
                || id.equals(Identifier.of("minecraft", "blast_protection"))
                || id.equals(Identifier.of("minecraft", "thorns"))
                || id.equals(Identifier.of("minecraft", "depth_strider"))
                || id.equals(Identifier.of("minecraft", "mending"))
                || id.equals(Identifier.of("minecraft", "soul_speed"));
    }

    /**
     * PlayerInventory is indexed as:
     * 0-8   = hotbar
     * 9-35  = main inventory
     *
     * AnvilScreenHandler adds the main inventory first and hotbar last:
     * handler 3-29 = main inventory
     * handler 30-38 = hotbar
     */
    private static int inventoryToHandlerSlot(int inventorySlot) {
        if (inventorySlot >= 0 && inventorySlot < 9) {
            return 30 + inventorySlot;
        }

        return 3 + (inventorySlot - 9);
    }

    private static void clickInventorySlot(
            MinecraftClient client,
            AnvilScreenHandler handler,
            int inventorySlot
    ) {
        client.interactionManager.clickSlot(
                handler.syncId,
                inventoryToHandlerSlot(inventorySlot),
                0,
                SlotActionType.QUICK_MOVE,
                client.player
        );
    }

    private static boolean isInputEmpty(AnvilScreenHandler handler) {
        return handler
                .getSlot(AnvilScreenHandler.INPUT_1_ID)
                .getStack()
                .isEmpty()
                && handler
                .getSlot(AnvilScreenHandler.INPUT_2_ID)
                .getStack()
                .isEmpty();
    }

    private static void stopWithError(
            MinecraftClient client,
            String text,
            int color
    ) {
        enabled = false;
        resetState();
        saveConfig();
        actionBar(client, text, color);
    }

    private static void resetState() {
        lastHandler = null;
        delay = 0;
        stage = Stage.IDLE;
        selectedBoot = ItemStack.EMPTY;
        selectedBook = ItemStack.EMPTY;
    }

    private static void actionBar(
            MinecraftClient client,
            String text,
            int color
    ) {
        if (client.player != null) {
            client.player.sendMessage(
                    Text.literal(text).styled(style -> style.withColor(color)),
                    true
            );
        }
    }

    private static java.nio.file.Path configPath() {
        return java.nio.file.Path.of(
                "config",
                "anvil_plus.properties"
        );
    }

    private static void loadConfig() {
        java.nio.file.Path path = configPath();

        if (!java.nio.file.Files.exists(path)) {
            return;
        }

        try {
            for (String line : java.nio.file.Files.readAllLines(path)) {
                if (line.startsWith("enabled=")) {
                    enabled = Boolean.parseBoolean(
                            line.substring("enabled=".length())
                    );
                }
            }
        } catch (java.io.IOException ignored) {
        }
    }

    private static void saveConfig() {
        java.nio.file.Path path = configPath();

        try {
            java.nio.file.Files.createDirectories(path.getParent());
            java.nio.file.Files.writeString(
                    path,
                    "enabled=" + enabled + System.lineSeparator()
            );
        } catch (java.io.IOException ignored) {
        }
    }
}
