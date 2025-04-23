package top.elusifrustra;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

public class ToolCascadeClient implements ClientModInitializer, ModMenuApi {
	public static final String MOD_ID = "tool-cascade";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	private static final Path CONFIG_PATH = FabricLoader.getInstance()
			.getConfigDir()
			.resolve(MOD_ID + ".properties");

	// Track item states before actions
	private final Map<Hand, PendingCheck> pendingChecks = new ConcurrentHashMap<>();

	// Default configuration values
	private static ToolCascadeConfig config = new ToolCascadeConfig(
			true,  // enableToolReplacement
			true,  // enableBlockReplacement
			false, // debugMode
			15      // toolCheckDelay
	);
	@Override
	public void onInitializeClient() {
		// Load config when mod initializes
		loadConfig();

		ToolCascadeClient.logDebug("Tool Cascade mod initializing - DEBUG MODE: " + config.DebugMode);

		// Reset tracking when connecting to server
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			ToolCascadeClient.logDebug("Tool Cascade: Connected to server, resetting state");
			pendingChecks.clear();
		});

		// Enhance the AttackBlockCallback to specifically track damage changes
		AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
			if (world.isClient) {
				ItemStack handStack = player.getStackInHand(hand);
				boolean replaceTools = config.EnableToolReplacement;

				if (!handStack.isEmpty() && handStack.isDamageable() && replaceTools) {
					// Record the current damage for later comparison
					int currentDamage = handStack.getDamage();
					int maxDamage = handStack.getMaxDamage();

					// Add more specific logging about the tool's condition
					ToolCascadeClient.logDebug("Tool Cascade: Tool use detected - " + handStack.getItem().getTranslationKey() + " (damage: " + currentDamage + "/" + maxDamage + ")");

					// Add a pending check only when tool is actually used
					PendingCheck check = new PendingCheck(handStack, true);

					// Adjust check delay based on how close to breaking the tool is
					if (maxDamage - currentDamage <= 1) {
						check.checkCounter = ToolCascadeClient.getConfig().ToolCheckDelay; // Faster check when tool is about to break
						ToolCascadeClient.logDebug("Tool Cascade: Tool is at critical durability, using shorter delay");
					}

					pendingChecks.put(hand, check);
				}
			}
			return ActionResult.PASS;
		});


		// Track item usage
		UseItemCallback.EVENT.register((player, world, hand) -> {
			if (world.isClient) {
				handleReplacementCheckScheduling(player, hand);
			}
			return ActionResult.PASS;
		});

		// Track block placement
		UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
			if (world.isClient && config.EnableBlockReplacement) {
				handleReplacementCheckScheduling(player, hand);
			}
			return ActionResult.PASS;
		});

		// Process pending checks with a tick delay
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (client.player != null && !pendingChecks.isEmpty() && (config.EnableBlockReplacement || config.EnableToolReplacement) ) {
				// Process any pending checks that have reached their delay
				for (Map.Entry<Hand, PendingCheck> entry : new HashMap<>(pendingChecks).entrySet()) {
					Hand hand = entry.getKey();
					PendingCheck check = entry.getValue();

					// Decrement counter and check if it's time to process
					check.checkCounter--;
					if (check.checkCounter <= 0) {
						ToolCascadeClient.logDebug("Tool Cascade: Processing delayed check for " + hand);
						if (check.isTool && config.EnableToolReplacement) {
							checkToolBreakage(client.player, hand, check.stack);
						} else {
							if (config.EnableBlockReplacement) {
								checkBlockPlacement(client.player, hand, check.stack);
							}
						}
						pendingChecks.remove(hand);
					}
				}
			}
		});
	}
	// Simple configuration class
	public static class ToolCascadeConfig {
		public boolean EnableToolReplacement;
		public boolean EnableBlockReplacement;
		public boolean DebugMode;
		public int ToolCheckDelay;

		public ToolCascadeConfig(boolean enableToolReplacement, boolean enableBlockReplacement, boolean debugMode, int toolCheckDelay) {
			this.EnableToolReplacement = enableToolReplacement;
			this.EnableBlockReplacement = enableBlockReplacement;
			this.DebugMode = debugMode;
			this.ToolCheckDelay = toolCheckDelay;
		}
	}
	private void handleReplacementCheckScheduling(PlayerEntity player, Hand hand) {
		ItemStack handStack = player.getStackInHand(hand);

		if (!handStack.isEmpty()) {
			boolean isTool = handStack.isDamageable();
			pendingChecks.put(hand, new PendingCheck(handStack, isTool));
			ToolCascadeClient.logDebug("Tool Cascade: Scheduled " + (isTool ? "tool" : "block") + " check for " + hand);
		}
	}

	// Load configuration from file
	private static void loadConfig() {
		try {
			if (!Files.exists(CONFIG_PATH)) {
				saveConfig(); // Create default config if it doesn't exist
				return;
			}

			Properties props = new Properties();
			try (InputStream is = Files.newInputStream(CONFIG_PATH)) {
				props.load(is);
			}

			config = new ToolCascadeConfig(
					Boolean.parseBoolean(props.getProperty("EnableToolReplacement", "true")),
					Boolean.parseBoolean(props.getProperty("EnableBlockReplacement", "true")),
					Boolean.parseBoolean(props.getProperty("DebugMode", "false")),
					Integer.parseInt(props.getProperty("ToolCheckDelay", "15"))
			);
		} catch (IOException e) {
			LOGGER.error("Failed to load config", e);
		}
	}

	// Save configuration to file
	public static void saveConfig() {
		try {
			Files.createDirectories(CONFIG_PATH.getParent());

			Properties props = new Properties();
			props.setProperty("EnableToolReplacement", String.valueOf(config.EnableToolReplacement));
			props.setProperty("EnableBlockReplacement", String.valueOf(config.EnableBlockReplacement));
			props.setProperty("DebugMode", String.valueOf(config.DebugMode));
			props.setProperty("ToolCheckDelay", String.valueOf(config.ToolCheckDelay));

			try (OutputStream os = Files.newOutputStream(CONFIG_PATH)) {
				props.store(os, "Tool Cascade Configuration");
			}
		} catch (IOException e) {
			LOGGER.error("Failed to save config", e);
		}
	}

	// Mod Menu integration
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return parent -> new ToolCascadeConfigScreen(parent);
	}

	// Simple config screen implementation
	public static class ToolCascadeConfigScreen extends Screen {
		private final Screen parent;
		private TextFieldWidget delayField;

		protected ToolCascadeConfigScreen(Screen parent) {
			super(Text.literal("Tool Cascade Config"));
			this.parent = parent;
		}

		@Override
		public void render(DrawContext context, int mouseX, int mouseY, float delta) {
			this.renderBackground(context, mouseX, mouseY, delta);

			context.drawCenteredTextWithShadow(
					this.textRenderer,
					this.title,
					this.width / 2,
					20,
					0xFFFFFF
			);
			super.render(context, mouseX, mouseY, delta);
		}


		@Override
		protected void init() {
			super.init();

			int y = this.height / 4 - 10;
			int buttonWidth = 200;
			int buttonHeight = 20;
			int x = this.width / 2 - buttonWidth / 2;

			// Tool Replacement Toggle
			this.addDrawableChild(ButtonWidget.builder(
					Text.literal("Tool Replacement: " + (config.EnableToolReplacement ? "ON" : "OFF")),
					button -> {
						config.EnableToolReplacement = !config.EnableToolReplacement;
						button.setMessage(Text.literal("Tool Replacement: " + (config.EnableToolReplacement ? "ON" : "OFF")));
						saveConfig();
					}
			).dimensions(x, y, buttonWidth, buttonHeight).build());
			y += 24;

			// Block Replacement Toggle
			this.addDrawableChild(ButtonWidget.builder(
					Text.literal("Block Replacement: " + (config.EnableBlockReplacement ? "ON" : "OFF")),
					button -> {
						config.EnableBlockReplacement = !config.EnableBlockReplacement;
						button.setMessage(Text.literal("Block Replacement: " + (config.EnableBlockReplacement ? "ON" : "OFF")));
						saveConfig();
					}
			).dimensions(x, y, buttonWidth, buttonHeight).build());
			y += 24;

			// Debug Mode Toggle
			this.addDrawableChild(ButtonWidget.builder(
					Text.literal("Debug Mode: " + (config.DebugMode ? "ON" : "OFF")),
					button -> {
						config.DebugMode = !config.DebugMode;
						button.setMessage(Text.literal("Debug Mode: " + (config.DebugMode ? "ON" : "OFF")));
						saveConfig();
					}
			).dimensions(x, y, buttonWidth, buttonHeight).build());
			y += 24;

			// Tool Check Delay - All on one line
			int delayY = y; // Keep same Y position as other elements

			// Label (using text rendering instead of button)
			this.addDrawableChild(new TextWidget(
					x, delayY + 4, 150, buttonHeight,
					Text.literal("Check Frequency (n-th tick):"),
					this.textRenderer
			).alignLeft());

			// Input field with current value
			this.delayField = new TextFieldWidget(
					this.textRenderer,
					x + 160, delayY, 40, buttonHeight,
					Text.literal("") // Set initial value
			);
			this.delayField.setMaxLength(3);
			this.delayField.setText(String.valueOf(config.ToolCheckDelay));
			this.delayField.setTextPredicate(text -> text.matches("\\d*"));
			this.addDrawableChild(this.delayField);

			// Save button moved near Done button
			int saveButtonY = this.height - 70; // Above Done button
			this.addDrawableChild(ButtonWidget.builder(
					Text.literal("Save Delay Setting"),
					button -> {
						try {
							int newDelay = Integer.parseInt(delayField.getText());
							if (newDelay >= 1 && newDelay <= 100) {
								config.ToolCheckDelay = newDelay;
								saveConfig();
								button.setMessage(Text.literal("Saved!"));
							} else {
								button.setMessage(Text.literal("Invalid (1-100)"));
							}
						} catch (NumberFormatException e) {
							button.setMessage(Text.literal("Numbers only!"));
						}
					}
			).dimensions(this.width / 2 - 100, saveButtonY, 200, buttonHeight).build());

			// Done button
			this.addDrawableChild(ButtonWidget.builder(
					Text.literal("Done"),
					button -> this.close()
			).dimensions(this.width / 2 - 100, this.height - 40, 200, 20).build());
		}
	}

	// Helper method to access config
	public static ToolCascadeConfig getConfig() {
		return config;
	}

	// Update your logging methods to use the config
	public static void logDebug(String message) {
		if (config.DebugMode) {
			LOGGER.info(message);
		}
	}

	// 1. Add a tracking field for the last used damage value to the PendingCheck class
	private static class PendingCheck {
		ItemStack stack;
		int checkCounter;
		boolean isTool;
		int lastDamage; // Track the damage value when we last checked

		PendingCheck(ItemStack stack, boolean isTool) {
			this.stack = stack.copy();
			this.checkCounter = 5; // Keep consistent delay
			this.isTool = isTool;
			this.lastDamage = stack.getDamage();
		}
	}

	// 2. Improve the checkToolBreakage method for better detection
	private void checkToolBreakage(PlayerEntity player, Hand hand, ItemStack preTool) {
		ToolCascadeClient.logDebug("Tool Cascade: Checking for tool breakage in " + hand);
		ItemStack currentTool = player.getStackInHand(hand);

		int preDamage = preTool.getDamage();
		int preMaxDamage = preTool.getMaxDamage();

		// Tool at critical durability before the action
		boolean wasAlmostBroken = (preMaxDamage - preDamage <= 1);

		if (currentTool.isEmpty()) {
			ToolCascadeClient.logDebug("Tool Cascade: Tool in " + hand + " is now empty (broke)");
			replaceBrokenTool(player, hand, preTool);
		} else if (!ItemStack.areItemsEqual(preTool, currentTool)) {
			// Using ItemStack.areItemsEqual for better comparison
			ToolCascadeClient.logDebug("Tool Cascade: Tool in " + hand + " was replaced with different item - likely broke");
			replaceBrokenTool(player, hand, preTool);
		} else if (wasAlmostBroken && currentTool.getDamage() > preDamage) {
			// Critical case: Tool was already at critical durability and now has more damage
			ToolCascadeClient.logDebug("Tool Cascade: Tool was at critical durability and has taken more damage");

			// Schedule one final check in 2 ticks to see if tool breaks after server processing
			PendingCheck finalCheck = new PendingCheck(currentTool, true);
			finalCheck.checkCounter = 2;
			finalCheck.lastDamage = currentTool.getDamage();
			pendingChecks.put(hand, finalCheck);
		} else if (preDamage > 0 && currentTool.getDamage() == 0 && ItemStack.areItemsEqual(preTool, currentTool)) {
			// Case where tool broke during a multi-hit sequence (check if damage reset to 0)
			ToolCascadeClient.logDebug("Tool Cascade: Tool damage reset to 0 - likely a server replacement");
			replaceBrokenTool(player, hand, preTool);
		} else {
			ToolCascadeClient.logDebug("Tool Cascade: Tool did not break in " + hand + " (damage: " + currentTool.getDamage() + "/" + currentTool.getMaxDamage() + ")");
		}
	}

	/**
	 * Checks if blocks have been placed
	 */
	private void checkBlockPlacement(PlayerEntity player, Hand hand, ItemStack preStack) {
		ToolCascadeClient.logDebug("Tool Cascade: Checking for block placement in " + hand);
		ItemStack currentStack = player.getStackInHand(hand);

		if (currentStack.isEmpty()) {
			ToolCascadeClient.logDebug("Tool Cascade: Last block placed from " + hand + " (hand now empty)");
			replenishBlocks(player, hand, preStack);
		}
		else if (ItemStack.areItemsEqual(currentStack, preStack) && currentStack.getCount() < preStack.getCount()) {
			// Just log the count decrease but don't replenish unless empty
			ToolCascadeClient.logDebug("Tool Cascade: Block count decreased in " + hand + " from " + preStack.getCount() + " to " + currentStack.getCount());
		} else {
			ToolCascadeClient.logDebug("ToolCascade: No block placement detected or hand contains different item");
		}
	}

	private void replaceBrokenTool(PlayerEntity player, Hand hand, ItemStack brokenItem) {
		ToolCascadeClient.logDebug("Tool Cascade: Attempting to replace broken tool: " + brokenItem.getItem().getTranslationKey());
		if (player == null || player.getInventory() == null) {
			ToolCascadeClient.logDebug("Tool Cascade: Player or inventory is null");
			return;
		}

		String brokenItemTranslationKey = brokenItem.getItem().getTranslationKey();
		MinecraftClient client = MinecraftClient.getInstance();

		for (int i = 0; i < player.getInventory().size(); i++) {
			ItemStack inventoryStack = player.getInventory().getStack(i);

			if (!inventoryStack.isEmpty() && inventoryStack.getItem().getTranslationKey().equals(brokenItemTranslationKey)) {

				ToolCascadeClient.logDebug("Tool Cascade: Found replacement tool at slot " + i);

				if (hand == Hand.MAIN_HAND) {
					int handSlot = player.getInventory().selectedSlot;
					ToolCascadeClient.logDebug("Tool Cascade: Replacing main hand item(" + handSlot + ") with item from slot " + i);

					// Convert inventory slot to container slot
					int containerSlot = getContainerSlotIndex(i);
					int handContainerSlot = getContainerSlotIndex(handSlot);

					ToolCascadeClient.logDebug("Tool Cascade: Using container slots: " + containerSlot + " -> " + handContainerSlot);

					// Perform the item movement
					MoveItems(player, client, containerSlot, handContainerSlot);
				} else {
					// Off-hand slot is 40 in the player screen handler
					int offHandContainerSlot = 40;
					int containerSlot = getContainerSlotIndex(i);

					ToolCascadeClient.logDebug("Tool Cascade: Using container slots: " + containerSlot + " -> " + offHandContainerSlot);

					// Pick up the replacement item
					MoveItems(player, client, containerSlot, offHandContainerSlot);
				}

				player.getInventory().markDirty();
				break;
			}
		}
	}

	private void MoveItems(PlayerEntity player, MinecraftClient client, int sourceSlot, int destSlot) {
		// First pick up the item from source
		client.interactionManager.clickSlot(
				player.playerScreenHandler.syncId,
				sourceSlot,
				0,
				SlotActionType.PICKUP,
				player);

		// Place in destination slot
		client.interactionManager.clickSlot(
				player.playerScreenHandler.syncId,
				destSlot,
				0,
				SlotActionType.PICKUP,
				player);

		// If cursor still has items, put back in original slot
		if (!player.currentScreenHandler.getCursorStack().isEmpty()) {
			client.interactionManager.clickSlot(
					player.playerScreenHandler.syncId,
					sourceSlot,
					0,
					SlotActionType.PICKUP,
					player);
		}
	}

	private void replenishBlocks(PlayerEntity player, Hand hand, ItemStack blockStack) {
		ToolCascadeClient.logDebug("Tool Cascade: Attempting to replenish blocks: " + blockStack.getItem().getTranslationKey());
		if (player == null || player.getInventory() == null) {
			return;
		}

		String blockTranslationKey = blockStack.getItem().getTranslationKey();
		MinecraftClient client = MinecraftClient.getInstance();

		// Search inventory for matching blocks
		for (int i = 0; i < player.getInventory().size(); i++) {
			// Skip if this is current hand slot
			int handSlot = (hand == Hand.MAIN_HAND) ? player.getInventory().selectedSlot : 40;
			if ((hand == Hand.MAIN_HAND && i == handSlot) || (hand == Hand.OFF_HAND && i == 40)) {
				continue;
			}

			ItemStack inventoryStack = player.getInventory().getStack(i);

			// Found matching blocks
			if (!inventoryStack.isEmpty() && inventoryStack.getItem().getTranslationKey().equals(blockTranslationKey)) {

				ToolCascadeClient.logDebug("Tool Cascade: Found replacement blocks at slot " + i);

				int containerSlot = getContainerSlotIndex(i);
				int handContainerSlot = (hand == Hand.MAIN_HAND) ? getContainerSlotIndex(handSlot) : 40;

				ToolCascadeClient.logDebug("Tool Cascade: Moving blocks from slot " + containerSlot + " to " + handContainerSlot);

				// Move the blocks
				MoveItems(player, client, containerSlot, handContainerSlot);

				player.getInventory().markDirty();
				break;
			}
		}

	}

	// Convert inventory slot index to container slot index
	private int getContainerSlotIndex(int inventorySlot) {
		// Hotbar slots (0-8) map to container slots 36-44
		if (inventorySlot >= 0 && inventorySlot <= 8) {
			return inventorySlot + 36;
		}
		// Main inventory slots (9-35) map to container slots 9-35
		else if (inventorySlot >= 9 && inventorySlot <= 35) {
			return inventorySlot;
		}
		// Armor slots (36-39) map to container slots 5-8
		else if (inventorySlot >= 36 && inventorySlot <= 39) {
			return inventorySlot - 31;
		}
		// Default fallback
		return inventorySlot;
	}
}
