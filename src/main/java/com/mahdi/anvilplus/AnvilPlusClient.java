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
import net.minecraft.enchantment.Enchantments;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.screen.AnvilScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class AnvilPlusClient implements ClientModInitializer {

    private static final String MOD_ID = "anvil_plus";

    /*
     * 3 ticks = approximately 150ms.
     */
    private static final int ACTION_DELAY_TICKS = 3;
    private static final int OPEN_DELAY_TICKS = 3;
    private static final int WAIT_TICKS = 5;

    private static final KeyBinding.Category CATEGORY =
            KeyBinding.Category.create(
                    Identifier.of(MOD_ID, "controls")
            );

    private static KeyBinding anvilPlusKey;
    private static KeyBinding enchantPlusKey;

    /*
     * These can ONLY be changed by their keybinds.
     * Errors or insufficient XP NEVER turn them OFF.
     */
    private static boolean anvilPlusEnabled = true;
    private static boolean enchantPlusEnabled = false;

    private static AnvilScreenHandler lastHandler;
    private static int delay = 0;

    private static Stage stage = Stage.IDLE;

    /*
     * Anvil Plus boot task.
     */
    private static ItemStack selectedBoot = ItemStack.EMPTY;
    private static ItemStack selectedBootBook = ItemStack.EMPTY;

    /*
     * Enchant Plus book task.
     */
    private static ItemStack selectedFirstBook = ItemStack.EMPTY;
    private static ItemStack selectedSecondBook = ItemStack.EMPTY;

    private enum Stage {
        IDLE,

        BOOT_WAITING_FOR_BOOT,
        BOOT_WAITING_FOR_BOOK,
        BOOT_WAITING_FOR_OUTPUT,
        BOOT_WAITING_FOR_CLEAR,

        ENCHANT_WAITING_FOR_FIRST,
        ENCHANT_WAITING_FOR_SECOND,
        ENCHANT_WAITING_FOR_OUTPUT,
        ENCHANT_WAITING_FOR_CLEAR
    }

    private static class BookPair {

        final RegistryKey<Enchantment> first;
        final int firstLevel;

        final RegistryKey<Enchantment> second;
        final int secondLevel;

        BookPair(
                RegistryKey<Enchantment> first,
                int firstLevel,
                RegistryKey<Enchantment> second,
                int secondLevel
        ) {
            this.first = first;
            this.firstLevel = firstLevel;

            this.second = second;
            this.secondLevel = secondLevel;
        }
    }

    /*
     * EXACT combinations for Enchant Plus.
     *
     * LEFT = Anvil Slot 1
     * RIGHT = Anvil Slot 2
     */
    private static final BookPair[] ENCHANT_PLUS_PAIRS = {

            new BookPair(
                    Enchantments.THORNS,
                    3,
                    Enchantments.MENDING,
                    1
            ),

            new BookPair(
                    Enchantments.BLAST_PROTECTION,
                    4,
                    Enchantments.FEATHER_FALLING,
                    4
            ),

            new BookPair(
                    Enchantments.DEPTH_STRIDER,
                    3,
                    Enchantments.UNBREAKING,
                    3
            )
    };

    @Override
    public void onInitializeClient() {

        /*
         * Existing Anvil Plus toggle.
         */
        anvilPlusKey = KeyBindingHelper.registerKeyBinding(
                new KeyBinding(
                        "key.anvil_plus.toggle",
                        InputUtil.Type.KEYSYM,
                        GLFW.GLFW_KEY_P,
                        CATEGORY
                )
        );

        /*
         * New Enchant Plus toggle.
         *
         * Default = O
         * Can be changed from Minecraft Controls.
         */
        enchantPlusKey = KeyBindingHelper.registerKeyBinding(
                new KeyBinding(
                        "key.anvil_plus.enchant_plus",
                        InputUtil.Type.KEYSYM,
                        GLFW.GLFW_KEY_O,
                        CATEGORY
                )
        );

        loadConfig();

        ClientTickEvents.END_CLIENT_TICK.register(
                AnvilPlusClient::tick
        );
    }

    private static void tick(MinecraftClient client) {

        /*
         * =====================================================
         * ANVIL PLUS TOGGLE
         * =====================================================
         */
        while (anvilPlusKey.wasPressed()) {

            anvilPlusEnabled = !anvilPlusEnabled;

            resetState();
            saveConfig();

            if (anvilPlusEnabled) {
                actionBar(
                        client,
                        "Anvil Plus: ON",
                        0x55FF55
                );
            } else {
                actionBar(
                        client,
                        "Anvil Plus: OFF",
                        0xFF5555
                );
            }
        }

        /*
         * =====================================================
         * ENCHANT PLUS TOGGLE
         * =====================================================
         */
        while (enchantPlusKey.wasPressed()) {

            enchantPlusEnabled = !enchantPlusEnabled;

            resetState();
            saveConfig();

            if (enchantPlusEnabled) {
                actionBar(
                        client,
                        "Enchant Plus: ON",
                        0xAA55FF
                );
            } else {
                actionBar(
                        client,
                        "Enchant Plus: OFF",
                        0xFF5555
                );
            }
        }

        /*
         * Nothing enabled.
         */
        if (!anvilPlusEnabled && !enchantPlusEnabled) {
            return;
        }

        if (client.player == null
                || client.interactionManager == null) {
            return;
        }

        /*
         * Both systems work ONLY while an Anvil is open.
         *
         * Closing the Anvil does NOT turn either feature OFF.
         */
        if (!(client.currentScreen instanceof AnvilScreen screen)) {

            resetState();

            return;
        }

        AnvilScreenHandler handler =
                screen.getScreenHandler();

        /*
         * New Anvil screen.
         */
        if (handler != lastHandler) {

            lastHandler = handler;

            stage = Stage.IDLE;

            delay = OPEN_DELAY_TICKS;

            clearSelections();

            return;
        }

        if (delay > 0) {

            delay--;

            return;
        }

        switch (stage) {

            case IDLE ->
                    chooseNextTask(
                            client,
                            handler
                    );

            /*
             * ==============================================
             * ANVIL PLUS
             * ==============================================
             */
            case BOOT_WAITING_FOR_BOOT ->
                    putBootBook(
                            client,
                            handler
                    );

            case BOOT_WAITING_FOR_BOOK ->
                    waitForBootInputs(
                            handler
                    );

            case BOOT_WAITING_FOR_OUTPUT ->
                    takeBootOutput(
                            client,
                            handler
                    );

            case BOOT_WAITING_FOR_CLEAR ->
                    waitForBootClear(
                            handler
                    );

            /*
             * ==============================================
             * ENCHANT PLUS
             * ==============================================
             */
            case ENCHANT_WAITING_FOR_FIRST ->
                    putSecondEnchantBook(
                            client,
                            handler
                    );

            case ENCHANT_WAITING_FOR_SECOND ->
                    waitForEnchantInputs(
                            handler
                    );

            case ENCHANT_WAITING_FOR_OUTPUT ->
                    takeEnchantOutput(
                            client,
                            handler
                    );

            case ENCHANT_WAITING_FOR_CLEAR ->
                    waitForEnchantClear(
                            handler
                    );
        }
    }

    /*
     * =========================================================
     * TASK SELECTION
     * =========================================================
     *
     * Enchant Plus gets priority.
     *
     * If no valid book pair exists,
     * normal Anvil Plus may process boots.
     */
    private static void chooseNextTask(
            MinecraftClient client,
            AnvilScreenHandler handler
    ) {

        if (!isInputEmpty(handler)) {

            delay = WAIT_TICKS;

            return;
        }

        /*
         * First try Enchant Plus.
         */
        if (enchantPlusEnabled) {

            BookPairResult pair =
                    findEnchantPlusPair(client);

            if (pair != null) {

                startEnchantPair(
                        client,
                        handler,
                        pair
                );

                return;
            }
        }

        /*
         * Then try normal Anvil Plus.
         */
        if (anvilPlusEnabled) {

            startNextBoot(
                    client,
                    handler
            );

            return;
        }

        delay = WAIT_TICKS;
    }

    /*
     * =========================================================
     * ENCHANT PLUS
     * =========================================================
     */

    private static class BookPairResult {

        final int firstSlot;
        final int secondSlot;

        BookPairResult(
                int firstSlot,
                int secondSlot
        ) {
            this.firstSlot = firstSlot;
            this.secondSlot = secondSlot;
        }
    }

    /*
     * Search ONLY for:
     *
     * Thorns III -> Mending
     *
     * Blast Protection IV -> Feather Falling IV
     *
     * Depth Strider III -> Unbreaking III
     *
     * It will NEVER intentionally pair:
     *
     * Thorns -> Unbreaking
     * Blast Protection -> Mending
     * etc.
     */
    private static BookPairResult findEnchantPlusPair(
            MinecraftClient client
    ) {

        for (BookPair pair : ENCHANT_PLUS_PAIRS) {

            int firstSlot =
                    findExactSingleEnchantBook(
                            client,
                            pair.first,
                            pair.firstLevel,
                            -1
                    );

            if (firstSlot < 0) {
                continue;
            }

            int secondSlot =
                    findExactSingleEnchantBook(
                            client,
                            pair.second,
                            pair.secondLevel,
                            firstSlot
                    );

            if (secondSlot < 0) {
                continue;
            }

            return new BookPairResult(
                    firstSlot,
                    secondSlot
            );
        }

        return null;
    }

    /*
     * For safety, Enchant Plus only automatically uses a book
     * containing EXACTLY ONE enchantment.
     *
     * This prevents an unexpected extra enchantment on a book
     * from creating a combination you didn't request.
     */
    private static int findExactSingleEnchantBook(
            MinecraftClient client,
            RegistryKey<Enchantment> wanted,
            int wantedLevel,
            int ignoredSlot
    ) {

        for (int i = 0;
             i < client.player.getInventory().size();
             i++) {

            if (i == ignoredSlot) {
                continue;
            }

            ItemStack stack =
                    client.player
                            .getInventory()
                            .getStack(i);

            if (stack.isEmpty()
                    || !stack.isOf(Items.ENCHANTED_BOOK)) {
                continue;
            }

            if (isExactSingleEnchantBook(
                    stack,
                    wanted,
                    wantedLevel
            )) {

                return i;
            }
        }

        return -1;
    }

    private static boolean isExactSingleEnchantBook(
            ItemStack book,
            RegistryKey<Enchantment> wanted,
            int wantedLevel
    ) {

        var enchantments =
                EnchantmentHelper.getEnchantments(book);

        /*
         * Must contain exactly ONE enchantment.
         */
        if (enchantments.getEnchantments().size() != 1) {
            return false;
        }

        for (RegistryEntry<Enchantment> entry :
                enchantments.getEnchantments()) {

            if (!entry.matchesKey(wanted)) {
                return false;
            }

            return enchantments.getLevel(entry)
                    == wantedLevel;
        }

        return false;
    }

    private static void startEnchantPair(
            MinecraftClient client,
            AnvilScreenHandler handler,
            BookPairResult pair
    ) {

        selectedFirstBook =
                client.player
                        .getInventory()
                        .getStack(pair.firstSlot)
                        .copy();

        selectedSecondBook =
                client.player
                        .getInventory()
                        .getStack(pair.secondSlot)
                        .copy();

        /*
         * FIRST requested book goes into Anvil Slot 1.
         */
        clickInventorySlot(
                client,
                handler,
                pair.firstSlot
        );

        stage =
                Stage.ENCHANT_WAITING_FOR_FIRST;

        delay =
                ACTION_DELAY_TICKS;
    }

    private static void putSecondEnchantBook(
            MinecraftClient client,
            AnvilScreenHandler handler
    ) {

        ItemStack firstInput =
                handler
                        .getSlot(
                                AnvilScreenHandler.INPUT_1_ID
                        )
                        .getStack();

        if (firstInput.isEmpty()) {

            delay = 1;

            return;
        }

        int secondSlot =
                findExactBook(
                        client,
                        selectedSecondBook
                );

        if (secondSlot < 0) {

            /*
             * Don't turn Enchant Plus OFF.
             */
            actionBar(
                    client,
                    "Enchant Plus: Second book not found",
                    0xFF5555
            );

            delay = WAIT_TICKS;

            return;
        }

        /*
         * SECOND requested book -> Anvil Slot 2.
         */
        clickInventorySlot(
                client,
                handler,
                secondSlot
        );

        stage =
                Stage.ENCHANT_WAITING_FOR_SECOND;

        delay =
                ACTION_DELAY_TICKS;
    }

    private static void waitForEnchantInputs(
            AnvilScreenHandler handler
    ) {

        ItemStack first =
                handler
                        .getSlot(
                                AnvilScreenHandler.INPUT_1_ID
                        )
                        .getStack();

        ItemStack second =
                handler
                        .getSlot(
                                AnvilScreenHandler.INPUT_2_ID
                        )
                        .getStack();

        if (first.isEmpty()
                || second.isEmpty()) {

            delay = 1;

            return;
        }

        stage =
                Stage.ENCHANT_WAITING_FOR_OUTPUT;

        delay =
                ACTION_DELAY_TICKS;
    }

    private static void takeEnchantOutput(
            MinecraftClient client,
            AnvilScreenHandler handler
    ) {

        int cost =
                handler.getLevelCost();

        ItemStack output =
                handler
                        .getSlot(
                                AnvilScreenHandler.OUTPUT_ID
                        )
                        .getStack();

        /*
         * Not enough XP:
         *
         * Do NOT turn OFF.
         * Leave both books inside the Anvil.
         * Wait until XP becomes sufficient.
         */
        if (cost > client.player.experienceLevel
                && cost <= 39) {

            actionBar(
                    client,
                    "Enchant Plus: Waiting for XP...",
                    0x5555FF
            );

            delay = WAIT_TICKS;

            return;
        }

        /*
         * Too Expensive:
         *
         * Still do NOT turn OFF.
         */
        if (cost > 39) {

            actionBar(
                    client,
                    "Enchant Plus: Too Expensive!",
                    0xFF5555
            );

            delay = 20;

            return;
        }

        if (output.isEmpty()) {

            actionBar(
                    client,
                    "Enchant Plus: Waiting for result...",
                    0xAA55FF
            );

            delay = WAIT_TICKS;

            return;
        }

        /*
         * Take combined enchanted book.
         */
        client.interactionManager.clickSlot(
                handler.syncId,
                AnvilScreenHandler.OUTPUT_ID,
                0,
                SlotActionType.QUICK_MOVE,
                client.player
        );

        actionBar(
                client,
                "Enchant Plus: Combined!",
                0x55FF55
        );

        stage =
                Stage.ENCHANT_WAITING_FOR_CLEAR;

        delay =
                ACTION_DELAY_TICKS;
    }

    private static void waitForEnchantClear(
            AnvilScreenHandler handler
    ) {

        if (!allAnvilSlotsEmpty(handler)) {

            delay = 1;

            return;
        }

        selectedFirstBook =
                ItemStack.EMPTY;

        selectedSecondBook =
                ItemStack.EMPTY;

        stage =
                Stage.IDLE;

        delay =
                ACTION_DELAY_TICKS;
    }

    /*
     * =========================================================
     * ORIGINAL ANVIL PLUS - NETHERITE BOOTS
     * =========================================================
     */

    private static void startNextBoot(
            MinecraftClient client,
            AnvilScreenHandler handler
    ) {

        if (!isInputEmpty(handler)) {

            delay = WAIT_TICKS;

            return;
        }

        int bootSlot =
                findNextBoot(client);

        if (bootSlot < 0) {

            delay = 10;

            return;
        }

        ItemStack boot =
                client.player
                        .getInventory()
                        .getStack(bootSlot);

        int bookSlot =
                findUsefulBootBook(
                        client,
                        boot
                );

        if (bookSlot < 0) {

            delay = 10;

            return;
        }

        selectedBoot =
                boot.copy();

        selectedBootBook =
                client.player
                        .getInventory()
                        .getStack(bookSlot)
                        .copy();

        clickInventorySlot(
                client,
                handler,
                bootSlot
        );

        stage =
                Stage.BOOT_WAITING_FOR_BOOT;

        delay =
                ACTION_DELAY_TICKS;
    }

    private static void putBootBook(
            MinecraftClient client,
            AnvilScreenHandler handler
    ) {

        ItemStack input =
                handler
                        .getSlot(
                                AnvilScreenHandler.INPUT_1_ID
                        )
                        .getStack();

        if (input.isEmpty()) {

            delay = 1;

            return;
        }

        int bookSlot =
                findExactBook(
                        client,
                        selectedBootBook
                );

        if (bookSlot < 0) {

            actionBar(
                    client,
                    "Anvil Plus: Book not found",
                    0xFF5555
            );

            delay = WAIT_TICKS;

            return;
        }

        clickInventorySlot(
                client,
                handler,
                bookSlot
        );

        stage =
                Stage.BOOT_WAITING_FOR_BOOK;

        delay =
                ACTION_DELAY_TICKS;
    }

    private static void waitForBootInputs(
            AnvilScreenHandler handler
    ) {

        ItemStack input1 =
                handler
                        .getSlot(
                                AnvilScreenHandler.INPUT_1_ID
                        )
                        .getStack();

        ItemStack input2 =
                handler
                        .getSlot(
                                AnvilScreenHandler.INPUT_2_ID
                        )
                        .getStack();

        if (input1.isEmpty()
                || input2.isEmpty()) {

            delay = 1;

            return;
        }

        stage =
                Stage.BOOT_WAITING_FOR_OUTPUT;

        delay =
                ACTION_DELAY_TICKS;
    }

    private static void takeBootOutput(
            MinecraftClient client,
            AnvilScreenHandler handler
    ) {

        int cost =
                handler.getLevelCost();

        ItemStack output =
                handler
                        .getSlot(
                                AnvilScreenHandler.OUTPUT_ID
                        )
                        .getStack();

        /*
         * Wait for XP.
         *
         * NEVER automatically turn Anvil Plus OFF.
         */
        if (cost > client.player.experienceLevel
                && cost <= 39) {

            actionBar(
                    client,
                    "Anvil Plus: Waiting for XP...",
                    0xAA55FF
            );

            delay = WAIT_TICKS;

            return;
        }

        if (cost > 39) {

            actionBar(
                    client,
                    "Anvil Plus: Too Expensive!",
                    0xFF5555
            );

            delay = 20;

            return;
        }

        if (output.isEmpty()) {

            delay = WAIT_TICKS;

            return;
        }

        client.interactionManager.clickSlot(
                handler.syncId,
                AnvilScreenHandler.OUTPUT_ID,
                0,
                SlotActionType.QUICK_MOVE,
                client.player
        );

        actionBar(
                client,
                "Anvil Plus: Enchanted!",
                0x55FF55
        );

        stage =
                Stage.BOOT_WAITING_FOR_CLEAR;

        delay =
                ACTION_DELAY_TICKS;
    }

    private static void waitForBootClear(
            AnvilScreenHandler handler
    ) {

        if (!allAnvilSlotsEmpty(handler)) {

            delay = 1;

            return;
        }

        selectedBoot =
                ItemStack.EMPTY;

        selectedBootBook =
                ItemStack.EMPTY;

        stage =
                Stage.IDLE;

        delay =
                ACTION_DELAY_TICKS;
    }

    private static int findNextBoot(
            MinecraftClient client
    ) {

        for (int i = 0;
             i < client.player.getInventory().size();
             i++) {

            ItemStack boot =
                    client.player
                            .getInventory()
                            .getStack(i);

            if (boot.isEmpty()
                    || !boot.isOf(Items.NETHERITE_BOOTS)) {
                continue;
            }

            /*
             * Skip a boot if none of the available books
             * can add anything new to it.
             */
            if (findUsefulBootBook(
                    client,
                    boot
            ) >= 0) {

                return i;
            }
        }

        return -1;
    }

    private static int findUsefulBootBook(
            MinecraftClient client,
            ItemStack boot
    ) {

        for (int i = 0;
             i < client.player.getInventory().size();
             i++) {

            ItemStack book =
                    client.player
                            .getInventory()
                            .getStack(i);

            if (book.isEmpty()
                    || !book.isOf(Items.ENCHANTED_BOOK)) {
                continue;
            }

            if (hasUsefulBootEnchantment(
                    boot,
                    book
            )) {

                return i;
            }
        }

        return -1;
    }

    private static boolean hasUsefulBootEnchantment(
            ItemStack boot,
            ItemStack book
    ) {

        var bootEnchantments =
                EnchantmentHelper.getEnchantments(boot);

        var bookEnchantments =
                EnchantmentHelper.getEnchantments(book);

        for (RegistryEntry<Enchantment> entry :
                bookEnchantments.getEnchantments()) {

            if (!isAllowedBootEnchantment(entry)) {
                continue;
            }

            int bookLevel =
                    bookEnchantments.getLevel(entry);

            int bootLevel =
                    bootEnchantments.getLevel(entry);

            /*
             * Already same/higher enchantment -> skip it.
             */
            if (bootLevel >= bookLevel) {
                continue;
            }

            if (!entry.value().isSupportedItem(boot)) {
                continue;
            }

            return true;
        }

        return false;
    }

    private static boolean isAllowedBootEnchantment(
            RegistryEntry<Enchantment> entry
    ) {

        return entry.matchesKey(
                Enchantments.UNBREAKING
        )
                || entry.matchesKey(
                Enchantments.FEATHER_FALLING
        )
                || entry.matchesKey(
                Enchantments.BLAST_PROTECTION
        )
                || entry.matchesKey(
                Enchantments.THORNS
        )
                || entry.matchesKey(
                Enchantments.DEPTH_STRIDER
        )
                || entry.matchesKey(
                Enchantments.MENDING
        )
                || entry.matchesKey(
                Enchantments.SOUL_SPEED
        );
    }

    /*
     * =========================================================
     * COMMON INVENTORY METHODS
     * =========================================================
     */

    private static int findExactBook(
            MinecraftClient client,
            ItemStack wanted
    ) {

        for (int i = 0;
             i < client.player.getInventory().size();
             i++) {

            ItemStack stack =
                    client.player
                            .getInventory()
                            .getStack(i);

            if (!stack.isEmpty()
                    && stack.isOf(Items.ENCHANTED_BOOK)
                    && ItemStack.areItemsAndComponentsEqual(
                            stack,
                            wanted
                    )) {

                return i;
            }
        }

        return -1;
    }

    /*
     * Player inventory:
     *
     * 0-8   Hotbar
     * 9-35  Main inventory
     *
     * Anvil handler:
     *
     * 3-29  Main inventory
     * 30-38 Hotbar
     */
    private static int inventoryToHandlerSlot(
            int inventorySlot
    ) {

        if (inventorySlot >= 0
                && inventorySlot < 9) {

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
                inventoryToHandlerSlot(
                        inventorySlot
                ),
                0,
                SlotActionType.QUICK_MOVE,
                client.player
        );
    }

    private static boolean isInputEmpty(
            AnvilScreenHandler handler
    ) {

        return handler
                .getSlot(
                        AnvilScreenHandler.INPUT_1_ID
                )
                .getStack()
                .isEmpty()

                && handler
                .getSlot(
                        AnvilScreenHandler.INPUT_2_ID
                )
                .getStack()
                .isEmpty();
    }

    private static boolean allAnvilSlotsEmpty(
            AnvilScreenHandler handler
    ) {

        return handler
                .getSlot(
                        AnvilScreenHandler.INPUT_1_ID
                )
                .getStack()
                .isEmpty()

                && handler
                .getSlot(
                        AnvilScreenHandler.INPUT_2_ID
                )
                .getStack()
                .isEmpty()

                && handler
                .getSlot(
                        AnvilScreenHandler.OUTPUT_ID
                )
                .getStack()
                .isEmpty();
    }

    private static void clearSelections() {

        selectedBoot =
                ItemStack.EMPTY;

        selectedBootBook =
                ItemStack.EMPTY;

        selectedFirstBook =
                ItemStack.EMPTY;

        selectedSecondBook =
                ItemStack.EMPTY;
    }

    /*
     * IMPORTANT:
     *
     * resetState() NEVER turns either feature OFF.
     */
    private static void resetState() {

        lastHandler = null;

        delay = 0;

        stage = Stage.IDLE;

        clearSelections();
    }

    /*
     * =========================================================
     * COLORED MESSAGES
     * =========================================================
     */

    private static void actionBar(
            MinecraftClient client,
            String text,
            int color
    ) {

        if (client.player != null) {

            client.player.sendMessage(
                    Text.literal(text)
                            .styled(
                                    style ->
                                            style.withColor(color)
                            ),
                    true
            );
        }
    }

    /*
     * =========================================================
     * CONFIG
     * =========================================================
     */

    private static Path configPath() {

        return Path.of(
                "config",
                "anvil_plus.properties"
        );
    }

    private static void loadConfig() {

        Path path =
                configPath();

        if (!Files.exists(path)) {
            return;
        }

        try {

            for (String line :
                    Files.readAllLines(path)) {

                if (line.startsWith(
                        "anvilPlusEnabled="
                )) {

                    anvilPlusEnabled =
                            Boolean.parseBoolean(
                                    line.substring(
                                            "anvilPlusEnabled=".length()
                                    )
                            );

                } else if (line.startsWith(
                        "enchantPlusEnabled="
                )) {

                    enchantPlusEnabled =
                            Boolean.parseBoolean(
                                    line.substring(
                                            "enchantPlusEnabled=".length()
                                    )
                            );

                /*
                 * Compatibility with old config.
                 */
                } else if (line.startsWith(
                        "enabled="
                )) {

                    anvilPlusEnabled =
                            Boolean.parseBoolean(
                                    line.substring(
                                            "enabled=".length()
                                    )
                            );
                }
            }

        } catch (IOException ignored) {
        }
    }

    private static void saveConfig() {

        Path path =
                configPath();

        try {

            Files.createDirectories(
                    path.getParent()
            );

            String data =
                    "anvilPlusEnabled="
                            + anvilPlusEnabled
                            + System.lineSeparator()

                            + "enchantPlusEnabled="
                            + enchantPlusEnabled
                            + System.lineSeparator();

            Files.writeString(
                    path,
                    data
            );

        } catch (IOException ignored) {
        }
    }
}
