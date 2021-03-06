package nl.themelvin.minetopiaeconomy;

import lombok.Getter;
import nl.themelvin.minetopiaeconomy.commands.AbstractCommand;
import nl.themelvin.minetopiaeconomy.commands.BalancetopCommand;
import nl.themelvin.minetopiaeconomy.commands.MoneyCommand;
import nl.themelvin.minetopiaeconomy.commands.EconomyCommand;
import nl.themelvin.minetopiaeconomy.listeners.AbstractListener;
import nl.themelvin.minetopiaeconomy.listeners.JoinListener;
import nl.themelvin.minetopiaeconomy.listeners.LoginListener;
import nl.themelvin.minetopiaeconomy.listeners.QuitListener;
import nl.themelvin.minetopiaeconomy.messaging.PluginMessaging;
import nl.themelvin.minetopiaeconomy.messaging.incoming.BalanceMessageHandler;
import nl.themelvin.minetopiaeconomy.messaging.incoming.PlayerMessageHandler;
import nl.themelvin.minetopiaeconomy.messaging.outgoing.BalanceMessage;
import nl.themelvin.minetopiaeconomy.messaging.outgoing.PlayerMessage;
import nl.themelvin.minetopiaeconomy.models.Model;
import nl.themelvin.minetopiaeconomy.models.Profile;
import nl.themelvin.minetopiaeconomy.storage.DataSaver;
import nl.themelvin.minetopiaeconomy.utils.Configuration;
import nl.themelvin.minetopiaeconomy.utils.Logger;
import nl.themelvin.minetopiaeconomy.utils.VaultHook;
import nl.themelvin.minetopiaeconomy.vault.EconomyHandler;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.ea.async.Async.await;
import static nl.themelvin.minetopiaeconomy.utils.Logger.*;

public class MinetopiaEconomy extends JavaPlugin {

    @Getter
    private static Plugin plugin;

    private static Configuration messages;
    private static Configuration config;

    @Getter
    private static HashMap<UUID, Profile> onlineProfiles = new HashMap<>();

    private Map<String, Class> commands = new HashMap<>();

    @Override
    public void onEnable() {

        plugin = this;

        // Log start
        log(Severity.INFO, "MinetopiaEconomy v" + this.getDescription().getVersion() + " wordt nu gestart.");
        long millis = System.currentTimeMillis();

        // Setup config
        config = new Configuration("config.yml");
        messages = new Configuration("messages.yml");

        // Create data folder
        new File(this.getDataFolder() + "/data").mkdir();

        // Setup MySQL / SQLite
        String storageType = config.getString("storage");

        if (!storageType.equalsIgnoreCase("mysql") && !storageType.equalsIgnoreCase("sqlite")) {
            log(Severity.ERROR, "Je hebt geen geldig opslagtype ingevuld, plugin wordt uitgeschakeld.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        if (!Model.startPool()) {
            Logger.log(Severity.ERROR, "Kon geen stabiele verbinding maken met de database, plugin wordt uitgeschakeld");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // Hook in Vault
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            log(Severity.WARNING, "Vault is niet in deze server geinstalleerd. Deze plugin werkt ook zonder Vault, maar dat is niet aangeraden.");
        } else {
            new VaultHook(EconomyHandler.class).hook();
        }

        // Register listeners
        this.registerListener(LoginListener.class);
        this.registerListener(JoinListener.class);
        this.registerListener(QuitListener.class);

        // Register commands
        this.registerCommand("money", MoneyCommand.class);
        this.registerCommand("balancetop", BalancetopCommand.class);
        this.registerCommand("economy", EconomyCommand.class);

        // Register plugin messaging
        if (config.getBoolean("plugin-messaging")) {

            if (!config.getString("storage").equalsIgnoreCase("mysql")) {

                log(Severity.ERROR, "Je hebt plugin messaging aangezet, maar het opslagtype staat nog niet op MySQL. Plugin messaging (en dus live synchronisatie) kan alleen ingeschakeld worden wanneer het opslagtype MySQL is.");
                Bukkit.getPluginManager().disablePlugin(this);
                return;

            }

            this.getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
            this.getServer().getMessenger().registerIncomingPluginChannel(this, "BungeeCord", PluginMessaging.getInstance());

            PluginMessaging messaging = PluginMessaging.getInstance();
            messaging.register("playerAction", PlayerMessage.class, PlayerMessageHandler.class);
            messaging.register("balanceChange", BalanceMessage.class, BalanceMessageHandler.class);

        }

        // Fake join for all online players
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {

            AsyncPlayerPreLoginEvent preLogin = new AsyncPlayerPreLoginEvent(onlinePlayer.getName(), onlinePlayer.getAddress().getAddress(), onlinePlayer.getUniqueId());
            new LoginListener(this).listen(preLogin);

            PlayerJoinEvent joinEvent = new PlayerJoinEvent(onlinePlayer, null);
            new JoinListener(this).listen(joinEvent);

        }

        // Save all data (async) every 5 minutes.
        Bukkit.getScheduler().runTaskTimer(this, new DataSaver(), 5 /*min*/ * 60 /*sec*/ * 20 /*ticks*/, 5 /*min*/ * 60 /*sec*/ * 20 /*ticks*/);

        // Log end
        log(Severity.INFO, "Klaar! Bedankt voor het gebruiken van deze plugin, het duurde " + (System.currentTimeMillis() - millis) + "ms.");

    }

    @Override
    public void onDisable() {

        log(Severity.INFO, "Alle online spelers worden opgeslagen.");

        // Save all online profiles
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {

            PlayerQuitEvent quitEvent = new PlayerQuitEvent(onlinePlayer, null);
            await(new QuitListener(this).listen(quitEvent));

        }

        // Close pool
        Model.closePool();

        log(Severity.INFO, "Uitgeschakeld! Bedankt voor het gebruiken van deze plugin.");

        plugin = null;

    }

    /**
     * Register command to command register.
     *
     * @param command Alias of the command
     * @param clazz   Cclass instance of the executor
     */

    private void registerCommand(String command, Class<?> clazz) {

        this.getCommand(command).setExecutor(this);
        this.commands.put(command, clazz);

        List<String> aliases = this.getCommand(command).getAliases();

        for (String alias : aliases) {

            this.commands.put(alias, clazz);

        }

    }

    private void registerListener(Class<? extends AbstractListener> clazz) {

        try {

            Constructor<?> constructor = clazz.getConstructor(Plugin.class);
            AbstractListener object = (AbstractListener) constructor.newInstance(this);
            object.register();

        } catch (Exception e) {

            e.printStackTrace();

        }

    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String alias, String[] args) {

        if (!this.commands.containsKey(alias)) {

            return false;

        }

        try {

            Class<?> clazz = this.commands.get(alias);
            Constructor<?> constructor = clazz.getConstructor(CommandSender.class, String.class, String[].class);
            AbstractCommand object = (AbstractCommand) constructor.newInstance(sender, alias, args);
            object.execute();

        } catch (Exception e) {

            e.printStackTrace();

        }

        return false;

    }

    public static Configuration messages() {

        return messages;

    }

    public static Configuration configuration() {

        return config;

    }

}
