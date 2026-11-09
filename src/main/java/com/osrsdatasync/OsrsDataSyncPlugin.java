package com.osrsdatasync;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.inject.Provides;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;
import net.runelite.http.api.loottracker.LootRecordType;

/**
 * Reads your own account's progress from the client and saves it under
 * .runelite/plugin-data/osrsdatasync/&lt;player&gt;/ as JSON, for spreadsheets, scripts or your own tools.
 * Read-only: it never sends input or menu actions, and nothing leaves the computer.
 *
 * <ul>
 * <li>progress.json: quests, quest points, diary tiers and tasks, combat achievement task ids, skills</li>
 * <li>killcounts.json: every monster/chest/clue from RuneLite's Loot Tracker + official counts from chat</li>
 * <li>clog.json: collection log items + amounts (open the log and press Search once), new-item messages</li>
 * <li>containers.json: bank (once opened), inventory, equipment, GE offers</li>
 * <li>loot.jsonl: one line per kill or reward (needs RuneLite's Loot Tracker for chests and clues)</li>
 * </ul>
 */
@Slf4j
@PluginDescriptor(
	name = "OSRS Data Sync",
	description = "Exports your own account data to local JSON files",
	tags = {"export", "json", "data", "collection log", "quests", "diaries", "loot", "bank", "kills"},
	internalName = "osrsdatasync"
)
public class OsrsDataSyncPlugin extends Plugin
{
	// Ticks are 0.6 s. Wait a few after login so the game has sent the account's varbits.
	private static final int LOGIN_DELAY_TICKS = 3;
	// Quests are read at login and whenever quest points change (a quest finished). Each read runs a game script per
	// quest, so there is no timer.
	// At most one write of the same file per 3 seconds.
	private static final int WRITE_GAP_TICKS = 5;

	// Script that draws one collection log item; fires for every obtained item when the log's Search is used.
	private static final int CLOG_ITEM_SCRIPT = 4100;

	private static final Pattern KILL_COUNT = Pattern.compile(
		"Your (?:completed |subdued )?(.+?) count is: ([0-9,]+)");
	private static final Pattern CLOG_NEW = Pattern.compile("New item added to your collection log: (.+)");

	private static final String LOOT_TRACKER_GROUP = "loottracker";

	// Loot sources whose official count comes from a differently named chat message (for the loot log's "kc")
	private static final Map<String, String> KC_NAMES = Map.of(
		"Corrupted Hunllef", "Corrupted Gauntlet completion",
		"Crystalline Hunllef", "Gauntlet completion");

	// Rune pouch: up to 6 slots (divine pouch), each a rune type (key of enum RUNEPOUCH_RUNE) + an amount.
	// The game keeps these runes out of every item container, so they are read here.
	private static final int[] POUCH_TYPES = {VarbitID.RUNE_POUCH_TYPE_1, VarbitID.RUNE_POUCH_TYPE_2, VarbitID.RUNE_POUCH_TYPE_3,
		VarbitID.RUNE_POUCH_TYPE_4, VarbitID.RUNE_POUCH_TYPE_5, VarbitID.RUNE_POUCH_TYPE_6};
	private static final int[] POUCH_AMOUNTS = {VarbitID.RUNE_POUCH_QUANTITY_1, VarbitID.RUNE_POUCH_QUANTITY_2,
		VarbitID.RUNE_POUCH_QUANTITY_3, VarbitID.RUNE_POUCH_QUANTITY_4, VarbitID.RUNE_POUCH_QUANTITY_5, VarbitID.RUNE_POUCH_QUANTITY_6};

	private static final String[] TIERS = {"Easy", "Medium", "Hard", "Elite"};
	private static final String[] DIARY_REGIONS = {
		"Ardougne", "Desert", "Falador", "Fremennik", "Kandarin", "Karamja", "Kourend & Kebos",
		"Lumbridge & Draynor", "Morytania", "Varrock", "Western Provinces", "Wilderness"};
	// Varbit that is set once a tier's tasks are all done; same order as DIARY_REGIONS x TIERS.
	private static final int[][] DIARY_VARBITS = {
		{VarbitID.ARDOUGNE_DIARY_EASY_COMPLETE, VarbitID.ARDOUGNE_DIARY_MEDIUM_COMPLETE, VarbitID.ARDOUGNE_DIARY_HARD_COMPLETE, VarbitID.ARDOUGNE_DIARY_ELITE_COMPLETE},
		{VarbitID.DESERT_DIARY_EASY_COMPLETE, VarbitID.DESERT_DIARY_MEDIUM_COMPLETE, VarbitID.DESERT_DIARY_HARD_COMPLETE, VarbitID.DESERT_DIARY_ELITE_COMPLETE},
		{VarbitID.FALADOR_DIARY_EASY_COMPLETE, VarbitID.FALADOR_DIARY_MEDIUM_COMPLETE, VarbitID.FALADOR_DIARY_HARD_COMPLETE, VarbitID.FALADOR_DIARY_ELITE_COMPLETE},
		{VarbitID.FREMENNIK_DIARY_EASY_COMPLETE, VarbitID.FREMENNIK_DIARY_MEDIUM_COMPLETE, VarbitID.FREMENNIK_DIARY_HARD_COMPLETE, VarbitID.FREMENNIK_DIARY_ELITE_COMPLETE},
		{VarbitID.KANDARIN_DIARY_EASY_COMPLETE, VarbitID.KANDARIN_DIARY_MEDIUM_COMPLETE, VarbitID.KANDARIN_DIARY_HARD_COMPLETE, VarbitID.KANDARIN_DIARY_ELITE_COMPLETE},
		// Karamja easy/medium/hard have no "complete" varbit (ATJUN_*_DONE is set before every task is done,
		// seen in-game 2026-11-01): KARAMJA_TASKS below decides those. -1 = not used.
		{-1, -1, -1, VarbitID.KARAMJA_DIARY_ELITE_COMPLETE},
		{VarbitID.KOUREND_DIARY_EASY_COMPLETE, VarbitID.KOUREND_DIARY_MEDIUM_COMPLETE, VarbitID.KOUREND_DIARY_HARD_COMPLETE, VarbitID.KOUREND_DIARY_ELITE_COMPLETE},
		{VarbitID.LUMBRIDGE_DIARY_EASY_COMPLETE, VarbitID.LUMBRIDGE_DIARY_MEDIUM_COMPLETE, VarbitID.LUMBRIDGE_DIARY_HARD_COMPLETE, VarbitID.LUMBRIDGE_DIARY_ELITE_COMPLETE},
		{VarbitID.MORYTANIA_DIARY_EASY_COMPLETE, VarbitID.MORYTANIA_DIARY_MEDIUM_COMPLETE, VarbitID.MORYTANIA_DIARY_HARD_COMPLETE, VarbitID.MORYTANIA_DIARY_ELITE_COMPLETE},
		{VarbitID.VARROCK_DIARY_EASY_COMPLETE, VarbitID.VARROCK_DIARY_MEDIUM_COMPLETE, VarbitID.VARROCK_DIARY_HARD_COMPLETE, VarbitID.VARROCK_DIARY_ELITE_COMPLETE},
		{VarbitID.WESTERN_DIARY_EASY_COMPLETE, VarbitID.WESTERN_DIARY_MEDIUM_COMPLETE, VarbitID.WESTERN_DIARY_HARD_COMPLETE, VarbitID.WESTERN_DIARY_ELITE_COMPLETE},
		{VarbitID.WILDERNESS_DIARY_EASY_COMPLETE, VarbitID.WILDERNESS_DIARY_MEDIUM_COMPLETE, VarbitID.WILDERNESS_DIARY_HARD_COMPLETE, VarbitID.WILDERNESS_DIARY_ELITE_COMPLETE},
	};
	private static final int KARAMJA = 5; // index in DIARY_REGIONS
	// Karamja easy/medium/hard: one varbit per task, consecutive ids (first, last); a tier is done when all are set.
	private static final int[][] KARAMJA_TASKS = {
		{VarbitID.ATJUN_EASY_BANANA, VarbitID.ATJUN_EASY_JOGRE},
		{VarbitID.ATJUN_MED_AGILITY, VarbitID.ATJUN_MED_CHARTER},
		{VarbitID.ATJUN_HARD_FIGHTPITS, VarbitID.ATJUN_HARD_DRAGON},
	};
	// Combat achievement task completion: task id N is bit N % 32 of the (N / 32)th varp.
	private static final int[] CA_VARPS = {
		VarPlayerID.CA_TASK_COMPLETED_0, VarPlayerID.CA_TASK_COMPLETED_1, VarPlayerID.CA_TASK_COMPLETED_2,
		VarPlayerID.CA_TASK_COMPLETED_3, VarPlayerID.CA_TASK_COMPLETED_4, VarPlayerID.CA_TASK_COMPLETED_5,
		VarPlayerID.CA_TASK_COMPLETED_6, VarPlayerID.CA_TASK_COMPLETED_7, VarPlayerID.CA_TASK_COMPLETED_8,
		VarPlayerID.CA_TASK_COMPLETED_9, VarPlayerID.CA_TASK_COMPLETED_10, VarPlayerID.CA_TASK_COMPLETED_11,
		VarPlayerID.CA_TASK_COMPLETED_12, VarPlayerID.CA_TASK_COMPLETED_13, VarPlayerID.CA_TASK_COMPLETED_14,
		VarPlayerID.CA_TASK_COMPLETED_15, VarPlayerID.CA_TASK_COMPLETED_16, VarPlayerID.CA_TASK_COMPLETED_17,
		VarPlayerID.CA_TASK_COMPLETED_18, VarPlayerID.CA_TASK_COMPLETED_19, VarPlayerID.CA_TASK_COMPLETED_20,
	};
	// The same ids as sets, for onVarbitChanged (which fires many times per tick)
	private static final Set<Integer> CA_VARP_SET = new HashSet<>();
	private static final Set<Integer> POUCH_VARBITS = new HashSet<>();
	private static final Set<Integer> DIARY_VARBIT_SET = new HashSet<>(); // tier "complete" varbits + Karamja task varbits
	private static final Set<Integer> DIARY_VARP_SET = new HashSet<>(); // varps holding diary task bits (DiaryTasks)

	static
	{
		for (int v : CA_VARPS)
		{
			CA_VARP_SET.add(v);
		}
		for (int i = 0; i < POUCH_TYPES.length; i++)
		{
			POUCH_VARBITS.add(POUCH_TYPES[i]);
			POUCH_VARBITS.add(POUCH_AMOUNTS[i]);
		}
		for (int[] region : DIARY_VARBITS)
		{
			for (int v : region)
			{
				if (v != -1)
				{
					DIARY_VARBIT_SET.add(v);
				}
			}
		}
		for (int[] range : KARAMJA_TASKS)
		{
			for (int v = range[0]; v <= range[1]; v++)
			{
				DIARY_VARBIT_SET.add(v);
			}
		}
		for (int[][][] region : DiaryTasks.TASKS)
		{
			for (int[][] tier : region)
			{
				for (int[] task : tier)
				{
					(task[0] == DiaryTasks.VARP ? DIARY_VARP_SET : DIARY_VARBIT_SET).add(task[1]);
				}
			}
		}
	}

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private Gson gson;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private ItemManager itemManager;

	@Inject
	private ConfigManager configManager;

	@Inject
	private OsrsDataSyncConfig config;

	@Inject
	private ClientToolbar clientToolbar;

	private Storage storage;
	private ItemStores stores;
	private ExportPanel panel;
	private NavigationButton navButton;
	private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

	// loot.jsonl lines not written yet (only while "Save Automatically" is off; written on Export or logout)
	private final List<String> pendingLoot = new ArrayList<>();

	// Account in use (folder name), set once the local player's name is known after login.
	private String player;
	// Saved kill counts and collection log are read back before they are written again.
	private boolean loaded;
	private int tick;
	private int loginDelay = -1;
	private int lastProgressWrite;
	private int lastContainersWrite;
	private boolean questsDue;
	private boolean progressDirty;
	private boolean containersDirty;
	private boolean killCountsDirty;
	private boolean clogDirty;
	// Tick at which to re-read the Loot Tracker's kill counts (a few ticks after a drop, so it has saved it)
	private int killCountsAt = -1;

	private final Map<String, Integer> quests = new LinkedHashMap<>();
	private final Map<String, Integer> killCounts = new TreeMap<>();
	private final Map<Integer, Integer> clogItems = new TreeMap<>();
	private final Map<String, String> clogNewByName = new TreeMap<>();
	private List<int[]> bank;
	private long bankAt; // when the bank was last seen open (ms since 1970); 0 = unknown
	private List<int[]> inventory = new ArrayList<>();
	private List<int[]> equipment = new ArrayList<>();
	// Kills with a drop this session, oldest first (see lootTrackerKills; trimmed once the Loot Tracker has saved them)
	private final List<RecentKill> recentKills = new ArrayList<>();
	// Loot Tracker records by config key, re-read only when their saved text changes (see lootTrackerKills)
	private final Map<String, LootCacheEntry> lootCache = new HashMap<>();

	@Provides
	OsrsDataSyncConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(OsrsDataSyncConfig.class);
	}

	@Override
	protected void startUp()
	{
		storage = new Storage(this::getPluginDirectory, executor);
		stores = new ItemStores(client, itemManager);
		panel = new ExportPanel(this::export, this::copyFolderPath);
		storage.setOnSaved(() ->
		{
			String time = LocalTime.now().format(CLOCK);
			SwingUtilities.invokeLater(() -> panel.saved(time));
		});
		BufferedImage icon = ImageUtil.loadImageResource(getClass(), "panel_icon.png");
		navButton = NavigationButton.builder()
			.tooltip("OSRS Data Sync")
			.icon(icon)
			.priority(10)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
		reset();
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			clientThread.invoke(() -> loginDelay = LOGIN_DELAY_TICKS);
		}
		log.debug("OSRS Data Sync started");
	}

	@Override
	protected void shutDown()
	{
		clientToolbar.removeNavigation(navButton);
		clientThread.invoke(() ->
		{
			flush();
			reset();
		});
		log.debug("OSRS Data Sync stopped");
	}

	private void reset()
	{
		player = null;
		loaded = false;
		loginDelay = -1;
		questsDue = progressDirty = containersDirty = killCountsDirty = clogDirty = false;
		killCountsAt = -1;
		quests.clear();
		lootCache.clear();
		killCounts.clear();
		clogItems.clear();
		clogNewByName.clear();
		bank = null;
		bankAt = 0;
		inventory = new ArrayList<>();
		equipment = new ArrayList<>();
		recentKills.clear();
		pendingLoot.clear();
		stores.reset();
		ExportPanel p = panel;
		SwingUtilities.invokeLater(() -> p.setPlayer(null));
	}

	// ---------------------------------------------------------------- events

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();
		if (state == GameState.LOGGED_IN && player == null)
		{
			loginDelay = LOGIN_DELAY_TICKS;
		}
		else if (state == GameState.LOGIN_SCREEN)
		{
			flush();
			reset();
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		tick++;
		if (loginDelay > 0)
		{
			loginDelay--;
			return;
		}
		if (loginDelay == 0 && !startSession())
		{
			return;
		}
		if (player == null)
		{
			return;
		}
		stores.onGameTick();
		if (stores.takeChanged())
		{
			containersDirty = true;
		}

		if (questsDue)
		{
			scanQuests();
		}
		if (!config.autoSave())
		{
			return; // changes are kept and written on Export or logout (flush)
		}
		writeLoot();
		if (progressDirty && tick - lastProgressWrite >= WRITE_GAP_TICKS)
		{
			writeProgress();
		}
		if (!loaded)
		{
			return; // containers.json too: the saved bank must be read back before the file is written again
		}
		if (containersDirty && tick - lastContainersWrite >= WRITE_GAP_TICKS)
		{
			writeContainers();
		}
		if (killCountsDirty || (killCountsAt >= 0 && tick >= killCountsAt))
		{
			writeKillCounts();
		}
		if (clogDirty)
		{
			writeClog();
		}
	}

	// Fires many times per tick, so it only does set lookups
	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		int varp = event.getVarpId();
		stores.onVarChanged(varp, event.getVarbitId());
		if (varp == VarPlayerID.QP)
		{
			questsDue = true;
		}
		if (DIARY_VARP_SET.contains(varp))
		{
			progressDirty = true;
		}
		if (CA_VARP_SET.contains(varp))
		{
			progressDirty = true;
			return;
		}
		int varbit = event.getVarbitId();
		if (varbit == -1)
		{
			return;
		}
		if (POUCH_VARBITS.contains(varbit))
		{
			containersDirty = true;
		}
		else if (DIARY_VARBIT_SET.contains(varbit))
		{
			progressDirty = true;
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		progressDirty = true;
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		int id = event.getContainerId();
		stores.onItemContainerChanged(id, event.getItemContainer());
		if (id == InventoryID.BANK)
		{
			bank = items(event.getItemContainer());
			bankAt = System.currentTimeMillis();
		}
		else if (id == InventoryID.INV)
		{
			inventory = items(event.getItemContainer());
		}
		else if (id == InventoryID.WORN)
		{
			equipment = items(event.getItemContainer());
		}
		else
		{
			return;
		}
		containersDirty = true;
	}

	/**
	 * Reads the bank, inventory and equipment the client already holds. The change events above only report
	 * changes, so after a start in the middle of a session (plugin switched on, client restarted with the bank
	 * open) a withdrawal made while the plugin was off would otherwise stay unseen: the bank kept last session's
	 * copy while the inventory had the item (seen 2026-11-03: a dragonfruit counted twice). The bank is only held
	 * once it has been opened this login; until then last session's copy stays (merge).
	 */
	private void readContainers()
	{
		ItemContainer bankNow = client.getItemContainer(InventoryID.BANK);
		if (bankNow != null)
		{
			bank = items(bankNow);
			bankAt = System.currentTimeMillis();
		}
		ItemContainer invNow = client.getItemContainer(InventoryID.INV);
		if (invNow != null)
		{
			inventory = items(invNow);
		}
		ItemContainer wornNow = client.getItemContainer(InventoryID.WORN);
		if (wornNow != null)
		{
			equipment = items(wornNow);
		}
		containersDirty = true;
	}

	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
	{
		containersDirty = true;
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM)
		{
			return;
		}
		String message = Text.removeTags(event.getMessage());
		stores.onChatMessage(message);

		Matcher kc = KILL_COUNT.matcher(message);
		if (kc.find())
		{
			String name = kc.group(1).replaceAll(" kill$", "");
			killCounts.put(name, Integer.parseInt(kc.group(2).replace(",", "")));
			killCountsDirty = true;
			return;
		}

		Matcher clog = CLOG_NEW.matcher(message);
		if (clog.find())
		{
			clogNewByName.put(clog.group(1).trim(), Instant.now().toString());
			clogDirty = true;
		}
	}

	// Opening the collection log and pressing Search draws every obtained item through this script.
	@Subscribe
	public void onScriptPreFired(ScriptPreFired event)
	{
		if (event.getScriptId() != CLOG_ITEM_SCRIPT || event.getScriptEvent() == null)
		{
			return;
		}
		Object[] args = event.getScriptEvent().getArguments();
		if (args == null || args.length < 3 || !(args[1] instanceof Integer) || !(args[2] instanceof Integer))
		{
			return;
		}
		int itemId = (Integer) args[1];
		int quantity = (Integer) args[2];
		if (itemId > 0 && quantity > 0)
		{
			clogItems.merge(itemId, quantity, Math::max);
			clogDirty = true;
		}
	}

	// Every drop the Loot Tracker records, the moment it records it: monster kills, raids, Barrows, clues and other
	// reward chests, pickpocketing. Monster kills used to come from NpcLootReceived (items on the floor), but loot
	// the game puts elsewhere never fires that (the Corrupted Hunllef's goes to the reward chest; seen 2026-11-02),
	// while the Loot Tracker gets it from the server (ServerNpcLoot). This way the loot log, the kill counts and
	// the Loot Tracker always agree.
	@Subscribe
	public void onLootReceived(LootReceived event)
	{
		LootRecordType type = event.getType();
		if (type == LootRecordType.NPC || type == LootRecordType.EVENT || type == LootRecordType.PICKPOCKET)
		{
			logLoot(event.getName(), type.name(), event.getItems());
		}
	}

	// ---------------------------------------------------------------- session

	/** Runs once the login delay is over; false while the player's name is not known yet. */
	private boolean startSession()
	{
		Player local = client.getLocalPlayer();
		if (local == null || local.getName() == null)
		{
			return false;
		}
		loginDelay = -1;
		player = safeName(local.getName());
		String shown = Text.toJagexName(local.getName());
		SwingUtilities.invokeLater(() -> panel.setPlayer(shown));
		showFolder();
		readContainers();
		storage.forget(); // write every file once this login, even if unchanged
		questsDue = true;
		progressDirty = true;
		containersDirty = true;
		load(player);
		return true;
	}

	private static class KillCountFile
	{
		Map<String, Integer> killCounts;
	}

	private static class ClogFile
	{
		Map<Integer, Integer> items;
		Map<String, String> newByName;
	}

	private static class ContainersFile
	{
		List<int[]> bank;
		Long bankAt;
		Map<String, ItemStores.Store> storage;
	}

	/** Reads the saved kill counts, collection log and bank, then merges them on the client thread. */
	private void load(String forPlayer)
	{
		storage.read(forPlayer, "killcounts.json", kcText ->
			storage.read(forPlayer, "clog.json", clogText ->
				storage.read(forPlayer, "containers.json", contText ->
				{
					KillCountFile kc = parse(kcText, KillCountFile.class);
					ClogFile clog = parse(clogText, ClogFile.class);
					ContainersFile cont = parse(contText, ContainersFile.class);
					clientThread.invoke(() -> merge(forPlayer, kc, clog, cont));
				})));
	}

	private void merge(String forPlayer, KillCountFile kc, ClogFile clog, ContainersFile cont)
	{
		if (!forPlayer.equals(player))
		{
			return; // logged out or switched account meanwhile
		}
		if (bank == null && cont != null && cont.bank != null)
		{
			// Not opened yet this session: keep the bank from last time (and when it was seen)
			bank = cont.bank;
			bankAt = cont.bankAt == null ? 0 : cont.bankAt;
		}
		if (cont != null)
		{
			stores.restore(cont.storage); // stores not read again yet this session keep last time's copy
		}
		containersDirty = true;
		if (kc != null && kc.killCounts != null)
		{
			// Counts seen this session are newer than the saved ones.
			kc.killCounts.forEach(killCounts::putIfAbsent);
		}
		if (clog != null)
		{
			if (clog.items != null)
			{
				clog.items.forEach((id, qty) -> clogItems.merge(id, qty, Math::max));
			}
			if (clog.newByName != null)
			{
				clog.newByName.forEach(clogNewByName::putIfAbsent);
			}
		}
		loaded = true;
		killCountsAt = tick; // export the Loot Tracker's kill counts once per login
		log.debug("Loaded saved data for {}", forPlayer);
	}

	private <T> T parse(String text, Class<T> type)
	{
		if (text == null)
		{
			return null;
		}
		try
		{
			return gson.fromJson(text, type);
		}
		catch (JsonSyntaxException e)
		{
			log.warn("Ignoring unreadable saved file ({})", type.getSimpleName(), e);
			return null;
		}
	}

	/** Writes whatever is still pending, e.g. on logout. */
	private void flush()
	{
		if (player == null)
		{
			return;
		}
		writeLoot();
		if (progressDirty)
		{
			writeProgress();
		}
		if (loaded)
		{
			if (containersDirty)
			{
				writeContainers();
			}
			if (killCountsDirty || killCountsAt >= 0)
			{
				writeKillCounts();
			}
			if (clogDirty)
			{
				writeClog();
			}
		}
	}

	// ---------------------------------------------------------------- progress.json

	// Quest.getState runs a game script, so this stays on the client thread and runs rarely.
	private void scanQuests()
	{
		questsDue = false;
		Map<String, Integer> now = new LinkedHashMap<>();
		for (Quest quest : Quest.values())
		{
			QuestState state = quest.getState(client);
			// 0 not started, 1 in progress, 2 finished
			now.put(quest.getName(), state == QuestState.FINISHED ? 2 : state == QuestState.IN_PROGRESS ? 1 : 0);
		}
		if (!now.equals(quests))
		{
			quests.clear();
			quests.putAll(now);
			progressDirty = true;
		}
	}

	private void writeProgress()
	{
		progressDirty = false;
		lastProgressWrite = tick;

		Map<String, Object> diaries = new LinkedHashMap<>();
		for (int r = 0; r < DIARY_REGIONS.length; r++)
		{
			Map<String, Boolean> tiers = new LinkedHashMap<>();
			for (int t = 0; t < TIERS.length; t++)
			{
				tiers.put(TIERS[t], DIARY_VARBITS[r][t] == -1 ? karamjaTierDone(t) : client.getVarbitValue(DIARY_VARBITS[r][t]) > 0);
			}
			diaries.put(DIARY_REGIONS[r], tiers);
		}

		// Each task as "1" (done) or "0", per region and tier, in the OSRS Wiki's task order
		Map<String, Object> diaryTasks = new LinkedHashMap<>();
		for (int r = 0; r < DIARY_REGIONS.length; r++)
		{
			Map<String, String> tiers = new LinkedHashMap<>();
			for (int t = 0; t < TIERS.length; t++)
			{
				StringBuilder flags = new StringBuilder();
				for (int[] task : DiaryTasks.TASKS[r][t])
				{
					flags.append(diaryTaskDone(task) ? '1' : '0');
				}
				tiers.put(TIERS[t], flags.toString());
			}
			diaryTasks.put(DIARY_REGIONS[r], tiers);
		}

		List<Integer> ca = new ArrayList<>();
		for (int i = 0; i < CA_VARPS.length; i++)
		{
			int bits = client.getVarpValue(CA_VARPS[i]);
			for (int b = 0; b < 32; b++)
			{
				if ((bits & (1 << b)) != 0)
				{
					ca.add(i * 32 + b);
				}
			}
		}

		Map<String, int[]> skills = new LinkedHashMap<>();
		int totalLevel = 0;
		for (Skill skill : Skill.values())
		{
			if ("OVERALL".equals(skill.name())) // the total, not a skill
			{
				continue;
			}
			int level = client.getRealSkillLevel(skill);
			totalLevel += level;
			skills.put(skill.getName(), new int[]{level, client.getSkillExperience(skill)});
		}

		Map<String, Object> data = new LinkedHashMap<>();
		data.put("player", client.getLocalPlayer() == null ? player : client.getLocalPlayer().getName());
		data.put("questPoints", client.getVarpValue(VarPlayerID.QP));
		data.put("quests", new LinkedHashMap<>(quests)); // a copy: the JSON is made on the executor
		data.put("diaries", diaries);
		data.put("diaryTasks", diaryTasks);
		data.put("ca", ca);
		data.put("totalLevel", totalLevel);
		data.put("totalXp", client.getOverallExperience());
		data.put("skills", skills);

		storage.writeJson(player, "progress.json", gson, data);
	}

	private boolean diaryTaskDone(int[] task)
	{
		return task[0] == DiaryTasks.VARP
			? (client.getVarpValue(task[1]) & (1 << task[2])) != 0
			: client.getVarbitValue(task[1]) > task[2];
	}

	private boolean karamjaTierDone(int tier)
	{
		for (int v = KARAMJA_TASKS[tier][0]; v <= KARAMJA_TASKS[tier][1]; v++)
		{
			if (client.getVarbitValue(v) == 0)
			{
				return false;
			}
		}
		return true;
	}

	// ---------------------------------------------------------------- containers.json

	// Runs on the client thread (item compositions). Skips empty slots, bank fillers and placeholders:
	// the bank sends placeholders with quantity 1 (seen in-game 2026-11-01: 160 of them).
	private List<int[]> items(ItemContainer container)
	{
		List<int[]> list = new ArrayList<>();
		if (container == null)
		{
			return list;
		}
		Item[] all = container.getItems();
		for (int slot = 0; slot < all.length; slot++)
		{
			Item item = all[slot];
			if (item.getId() >= 0 && item.getQuantity() > 0 && item.getId() != ItemID.BANK_FILLER
				&& itemManager.getItemComposition(item.getId()).getPlaceholderTemplateId() == -1)
			{
				// Noted items (inventory) as the item itself, so they have a name, picture and price.
				// Third value: the slot, so the inventory can be laid out like the game.
				list.add(new int[]{itemManager.canonicalize(item.getId()), item.getQuantity(), slot});
			}
		}
		return list;
	}

	/** Runes in the rune pouch as [item id, amount] (client thread). Empty when there is no pouch or it's empty. */
	private List<int[]> runePouch()
	{
		List<int[]> list = new ArrayList<>();
		EnumComposition runes = client.getEnum(EnumID.RUNEPOUCH_RUNE);
		for (int i = 0; i < POUCH_TYPES.length; i++)
		{
			int type = client.getVarbitValue(POUCH_TYPES[i]);
			int amount = client.getVarbitValue(POUCH_AMOUNTS[i]);
			if (type <= 0 || amount <= 0)
			{
				continue;
			}
			int id = runes.getIntValue(type);
			if (id > 0)
			{
				list.add(new int[]{id, amount});
			}
		}
		return list;
	}

	private void writeContainers()
	{
		containersDirty = false;
		lastContainersWrite = tick;

		List<Map<String, Object>> ge = new ArrayList<>();
		GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		if (offers != null)
		{
			for (int slot = 0; slot < offers.length; slot++)
			{
				GrandExchangeOffer o = offers[slot];
				if (o == null || o.getState() == GrandExchangeOfferState.EMPTY)
				{
					continue;
				}
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("slot", slot + 1);
				m.put("state", o.getState().name());
				m.put("id", o.getItemId());
				m.put("total", o.getTotalQuantity());
				m.put("done", o.getQuantitySold());
				m.put("price", o.getPrice());
				m.put("spent", o.getSpent());
				ge.add(m);
			}
		}

		Map<String, Object> data = new LinkedHashMap<>();
		// The bank is only known once it has been opened: until then, the one saved last session (see merge).
		// Never written without it, or a restart would blank the bank in the file.
		if (bank != null)
		{
			data.put("bank", bank);
			data.put("bankAt", bankAt);
		}
		data.put("inventory", inventory);
		data.put("equipment", equipment);
		data.put("runePouch", runePouch());
		data.put("ge", ge);
		// Items inside other items and in other storage (ItemStores); a copy, as the JSON is made on the executor
		data.put("storage", new TreeMap<>(stores.all()));
		// bank / inventory / equipment lists are replaced (never changed in place) on each change, so they can be
		// handed to the executor as they are
		storage.writeJson(player, "containers.json", gson, data);
	}

	// ---------------------------------------------------------------- kill counts, collection log

	private void writeKillCounts()
	{
		killCountsDirty = false;
		killCountsAt = -1;
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("killCounts", new TreeMap<>(killCounts)); // official counts from the game's chat messages (bosses, raids, ...)
		data.put("lootTracker", lootTrackerKills()); // every monster, chest and clue with a drop (fresh objects)
		storage.writeJson(player, "killcounts.json", gson, data);
	}

	private static class LootTrackerRecord
	{
		String type;
		String name;
		int kills;
		long first;
		long last;
		int[] drops; // item id, quantity, item id, quantity, ...
	}

	/** One monster/chest/clue in killcounts.json. */
	private static class KillEntry
	{
		// Not written to the file (the file is keyed by type and name); transient keeps Gson from writing them
		transient String type;
		transient String name;
		long kills;
		long first;
		long last;
		Map<Integer, Long> drops = new TreeMap<>(); // item id -> total quantity

		/** A copy that can be changed (this session's kills added) without touching the cached one. */
		KillEntry copy()
		{
			KillEntry c = new KillEntry();
			c.type = type;
			c.name = name;
			c.kills = kills;
			c.first = first;
			c.last = last;
			c.drops = new TreeMap<>(drops);
			return c;
		}
	}

	/**
	 * Kills and total drops per monster from RuneLite's Loot Tracker, which counts every kill that drops
	 * something, for this account and game mode (its "rsprofile"; leagues etc. are separate).
	 * {type: {name: {kills, first, last, drops: {itemId: qty}}}}, times in ms since 1970.
	 * Empty when the Loot Tracker plugin has never run.
	 */
	private Map<String, Map<String, KillEntry>> lootTrackerKills()
	{
		Map<String, Map<String, KillEntry>> out = new TreeMap<>();
		String profile = configManager.getRSProfileKey();
		if (profile == null)
		{
			return out;
		}
		Set<String> seen = new HashSet<>();
		for (String key : configManager.getRSProfileConfigurationKeys(LOOT_TRACKER_GROUP, profile, "drops_"))
		{
			seen.add(key);
			String raw = configManager.getRSProfileConfiguration(LOOT_TRACKER_GROUP, key);
			// Parsed once per change of the Loot Tracker's record (it saves every few minutes), not on every kill
			LootCacheEntry c = lootCache.get(key);
			if (c == null || !Objects.equals(c.raw, raw))
			{
				c = new LootCacheEntry(raw, toKillEntry(parse(raw, LootTrackerRecord.class)));
				lootCache.put(key, c);
			}
			if (c.entry != null)
			{
				out.computeIfAbsent(c.entry.type, t -> new TreeMap<>()).put(c.entry.name, c.entry.copy());
			}
		}
		lootCache.keySet().retainAll(seen); // records the Loot Tracker deleted (its "reset")

		// The Loot Tracker writes its records to disk only every few minutes (seen in-game 2026-11-01: a kill
		// 2 s old was missing). Add this session's kills that came after its saved "last" time. Both plugins
		// stamp the same drop a few ms apart, so a kill within 1 s of that time is the one already saved;
		// those are dropped from recentKills, so the list stays small on long sessions.
		Map<String, Long> savedLast = new HashMap<>();
		for (Iterator<RecentKill> it = recentKills.iterator(); it.hasNext(); )
		{
			RecentKill k = it.next();
			Map<String, KillEntry> byName = out.computeIfAbsent(k.type, t -> new TreeMap<>());
			KillEntry existing = byName.get(k.name);
			long saved = savedLast.computeIfAbsent(k.type + "\n" + k.name, x -> existing == null ? 0 : existing.last);
			if (k.time <= saved + 1000)
			{
				it.remove(); // already in the Loot Tracker's saved record
				continue;
			}
			KillEntry e = byName.computeIfAbsent(k.name, n ->
			{
				KillEntry fresh = new KillEntry();
				fresh.first = k.time;
				return fresh;
			});
			e.kills++;
			e.last = Math.max(e.last, k.time);
			for (Map.Entry<Integer, Long> d : k.items.entrySet())
			{
				e.drops.merge(d.getKey(), d.getValue(), Long::sum);
			}
		}
		return out;
	}

	/** One Loot Tracker record as a KillEntry, noted drops counted as the item (client thread: canonicalize). */
	private KillEntry toKillEntry(LootTrackerRecord r)
	{
		if (r == null || r.name == null || r.type == null)
		{
			return null;
		}
		KillEntry e = new KillEntry();
		e.type = r.type;
		e.name = r.name;
		e.kills = r.kills;
		e.first = r.first;
		e.last = r.last;
		if (r.drops != null)
		{
			for (int i = 0; i + 1 < r.drops.length; i += 2)
			{
				// Noted drops (e.g. noted bones) have their own id with no price or picture: count them as the item
				e.drops.merge(itemManager.canonicalize(r.drops[i]), (long) r.drops[i + 1], Long::sum);
			}
		}
		return e;
	}

	/** A Loot Tracker record as last read: its saved text and what it was turned into. */
	private static class LootCacheEntry
	{
		final String raw;
		final KillEntry entry; // null when the record couldn't be read

		LootCacheEntry(String raw, KillEntry entry)
		{
			this.raw = raw;
			this.entry = entry;
		}
	}

	private static class RecentKill
	{
		final String type;
		final String name;
		final long time;
		final Map<Integer, Long> items;

		RecentKill(String type, String name, long time, Map<Integer, Long> items)
		{
			this.type = type;
			this.name = name;
			this.time = time;
			this.items = items;
		}
	}

	private void writeClog()
	{
		clogDirty = false;
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("obtained", clogItems.size());
		data.put("items", new TreeMap<>(clogItems)); // copies: the JSON is made on the executor
		data.put("newByName", new TreeMap<>(clogNewByName));
		storage.writeJson(player, "clog.json", gson, data);
	}

	// ---------------------------------------------------------------- loot log

	private void logLoot(String source, String type, Collection<ItemStack> stacks)
	{
		if (player == null || stacks == null || stacks.isEmpty())
		{
			return;
		}
		Instant now = Instant.now();
		Map<Integer, Long> merged = new LinkedHashMap<>();
		for (ItemStack s : stacks)
		{
			merged.merge(itemManager.canonicalize(s.getId()), (long) s.getQuantity(), Long::sum);
		}
		// Duplicates of collection log items: the game posts no chat message for them, and the log's counts are only
		// read when it's opened (Search). A drop of an item already in the log adds to its count here, right away
		// (user 2026-11-02: a crystal weapon seed didn't show). Search later keeps the higher count, so a drop is
		// never counted twice. Only once the saved log has been read back (loaded), and only items already in it.
		if (loaded)
		{
			for (Map.Entry<Integer, Long> e : merged.entrySet())
			{
				Integer have = clogItems.get(e.getKey());
				if (have != null)
				{
					clogItems.put(e.getKey(), (int) Math.min((long) have + e.getValue(), Integer.MAX_VALUE));
					clogDirty = true;
				}
			}
		}
		List<long[]> lines = new ArrayList<>();
		long total = 0;
		for (Map.Entry<Integer, Long> e : merged.entrySet())
		{
			long each = itemManager.getItemPrice(e.getKey());
			lines.add(new long[]{e.getKey(), e.getValue(), each});
			total += each * e.getValue();
		}

		Map<String, Object> entry = new LinkedHashMap<>();
		entry.put("t", now.toString());
		entry.put("source", source);
		entry.put("type", type);
		entry.put("value", total);
		entry.put("items", lines); // [item id, quantity, gp each]
		Integer kc = source == null ? null : killCounts.get(KC_NAMES.getOrDefault(source, source));
		if (kc != null)
		{
			entry.put("kc", kc);
		}
		pendingLoot.add(gson.toJson(entry));
		if (config.autoSave())
		{
			writeLoot(); // right away, so a crash can't lose it
		}
		if (source != null)
		{
			recentKills.add(new RecentKill(type, source, now.toEpochMilli(), merged));
		}
		if (killCountsAt < 0)
		{
			killCountsAt = tick + WRITE_GAP_TICKS;
		}
	}

	private void writeLoot()
	{
		if (!pendingLoot.isEmpty())
		{
			storage.append(player, "loot.jsonl", new ArrayList<>(pendingLoot));
			pendingLoot.clear();
		}
	}

	// ---------------------------------------------------------------- side panel

	/** Export button (Swing thread): re-reads quests and saves every file now, on the client thread. */
	private void export()
	{
		clientThread.invoke(() ->
		{
			if (player == null)
			{
				SwingUtilities.invokeLater(() -> panel.message("Log in to export"));
				return;
			}
			if (!loaded)
			{
				// The saved bank, collection log and kill counts are still being read back; writing now would drop them
				SwingUtilities.invokeLater(() -> panel.message("Still loading, try again"));
				return;
			}
			scanQuests();
			readContainers();
			storage.forget(); // write every file, even if unchanged, so each gets a fresh "updated" time
			progressDirty = containersDirty = killCountsDirty = clogDirty = true;
			flush();
			storage.after(() ->
			{
				String time = LocalTime.now().format(CLOCK);
				SwingUtilities.invokeLater(() -> panel.exported(time));
			});
		});
	}

	/** Copy Folder Path button (Swing thread): the folder goes to the clipboard, to paste into a file browser. */
	private void copyFolderPath()
	{
		clientThread.invoke(() -> storage.folderPath(player, path -> SwingUtilities.invokeLater(() ->
		{
			Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(path), null);
			panel.setFolder(path);
			panel.message("Folder path copied");
		})));
	}

	/** Shows the account's folder in the panel (client thread). */
	private void showFolder()
	{
		storage.folderPath(player, path -> SwingUtilities.invokeLater(() -> panel.setFolder(path)));
	}

	// ---------------------------------------------------------------- helpers

	// Player names can hold spaces (sent as non-breaking spaces); keep the folder name plain.
	static String safeName(String name)
	{
		String s = name.replace((char) 160, ' ').trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "_");
		return s.isEmpty() ? "unknown" : s;
	}
}
