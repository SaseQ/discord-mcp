package dev.saseq.services;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class CategoryService {

    private final JDA jda;

    @Value("${DISCORD_GUILD_ID:}")
    private String defaultGuildId;

    public CategoryService(JDA jda) {
        this.jda = jda;
    }

    private String resolveGuildId(String guildId) {
        if ((guildId == null || guildId.isEmpty()) && defaultGuildId != null && !defaultGuildId.isEmpty()) {
            return defaultGuildId;
        }
        return guildId;
    }

    /**
     * Creates a new channel category in a specified Discord server.
     *
     * @param guildId Optional ID of the Discord server (guild). If not provided, the default server will be used.
     * @param name    The name for the new category.
     * @return A confirmation message with the name of the created category.
     */
    @Tool(name = "create_category", description = "Create a new category for channels")
    public String createCategory(@ToolParam(description = "Discord server ID", required = false) String guildId,
                                 @ToolParam(description = "Discord category name") String name) {
        guildId = resolveGuildId(guildId);
        if (guildId == null || guildId.isEmpty()) {
            throw new IllegalArgumentException("guildId cannot be null");
        }
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("name cannot be null");
        }

        Guild guild = jda.getGuildById(guildId);
        if (guild == null) {
            throw new IllegalArgumentException("Discord server not found by guildId");
        }
        Category category = guild.createCategory(name).complete();
        return "Created new category: " + category.getName();
    }

    /**
     * Deletes a specified channel category from a Discord server.
     *
     * @param guildId    Optional ID of the Discord server (guild). If not provided, the default server will be used.
     * @param categoryId The ID of the category to be deleted.
     * @return A confirmation message with the name of the deleted category.
     */
    @Tool(name = "delete_category", description = "Delete a category")
    public String deleteCategory(@ToolParam(description = "Discord server ID", required = false) String guildId,
                                 @ToolParam(description = "Discord category ID") String categoryId) {
        guildId = resolveGuildId(guildId);
        if (guildId == null || guildId.isEmpty()) {
            throw new IllegalArgumentException("guildId cannot be null");
        }
        if (categoryId == null || categoryId.isEmpty()) {
            throw new IllegalArgumentException("categoryId cannot be null");
        }

        Guild guild = jda.getGuildById(guildId);
        if (guild == null) {
            throw new IllegalArgumentException("Discord server not found by guildId");
        }
        Category category = guild.getCategoryById(categoryId);
        if (category == null) {
            throw new IllegalArgumentException("Category not found by categoryId");
        }
        category.delete().queue();
        return "Deleted category: " + category.getName();
    }

    /**
     * Finds a channel category by its name within a specified Discord server and returns its ID.
     *
     * @param guildId      Optional ID of the Discord server (guild). If not provided, the default server will be used.
     * @param categoryName The name of the category to find.
     * @return A message containing the name and ID of the found category.
     */
    @Tool(name = "edit_category", description = "Edit a category (rename or move position)")
    public String editCategory(@ToolParam(description = "Discord server ID", required = false) String guildId,
                               @ToolParam(description = "Discord category ID") String categoryId,
                               @ToolParam(description = "New category name", required = false) String name,
                               @ToolParam(description = "New position in channel list", required = false) String position,
                               @ToolParam(description = "Reason for audit log", required = false) String reason) {
        guildId = resolveGuildId(guildId);
        if (guildId == null || guildId.isEmpty()) {
            throw new IllegalArgumentException("guildId cannot be null");
        }
        if (categoryId == null || categoryId.isEmpty()) {
            throw new IllegalArgumentException("categoryId cannot be null");
        }
        Guild guild = jda.getGuildById(guildId);
        if (guild == null) {
            throw new IllegalArgumentException("Discord server not found by guildId");
        }
        Category category = guild.getCategoryById(categoryId);
        if (category == null) {
            throw new IllegalArgumentException("Category not found by categoryId");
        }
        if ((name == null || name.isEmpty()) && (position == null || position.isEmpty())) {
            throw new IllegalArgumentException("At least one of 'name' or 'position' must be provided");
        }
        var manager = category.getManager();
        if (name != null && !name.isEmpty()) manager.setName(name);
        if (position != null && !position.isEmpty()) manager.setPosition(Integer.parseInt(position));
        if (reason != null && !reason.isEmpty()) manager.reason(reason);
        manager.complete();
        return "Updated category: " + category.getName() + " (ID: " + category.getId() + ")";
    }

    @Tool(name = "find_category", description = "Find a category ID using name and server ID")
    public String findCategory(@ToolParam(description = "Discord server ID", required = false) String guildId,
                               @ToolParam(description = "Discord category name") String categoryName) {
        guildId = resolveGuildId(guildId);
        if (guildId == null || guildId.isEmpty()) {
            throw new IllegalArgumentException("guildId cannot be null");
        }
        if (categoryName == null || categoryName.isEmpty()) {
            throw new IllegalArgumentException("categoryName cannot be null");
        }

        Guild guild = jda.getGuildById(guildId);
        if (guild == null) {
            throw new IllegalArgumentException("Discord server not found by guildId");
        }
        List<Category> categories = guild.getCategoriesByName(categoryName, true);
        if (categories.isEmpty()) {
            throw new IllegalArgumentException("Category " + categoryName + " not found");
        }
        if (categories.size() > 1) {
            String channelList = categories.stream()
                    .map(c -> "**" + c.getName() + "** - `" + c.getId() + "`")
                    .collect(Collectors.joining(", "));
            throw new IllegalArgumentException("Multiple channels found with name " + categoryName + ".\n" +
                    "List: " + channelList + ".\nPlease specify the channel ID.");
        }
        Category category = categories.get(0);
        return "Retrieved category: " + category.getName() + ", with ID: " + category.getId();
    }

    /**
     * Lists all channels within a specified category in a Discord server.
     *
     * @param guildId    Optional ID of the Discord server (guild). If not provided, the default server will be used.
     * @param categoryId The ID of the category from which to list channels.
     * @return A formatted string listing the channels in the category, including their type, name, and ID.
     */
    @Tool(name = "list_channels_in_category", description = "List of channels in a specific category")
    public String listChannelsInCategory(@ToolParam(description = "Discord server ID", required = false) String guildId,
                                         @ToolParam(description = "Discord category ID") String categoryId) {
        guildId = resolveGuildId(guildId);
        if (guildId == null || guildId.isEmpty()) {
            throw new IllegalArgumentException("guildId cannot be null");
        }
        if (categoryId == null || categoryId.isEmpty()) {
            throw new IllegalArgumentException("categoryId cannot be null");
        }

        Guild guild = jda.getGuildById(guildId);
        if (guild == null) {
            throw new IllegalArgumentException("Discord server not found by guildId");
        }
        Category category = guild.getCategoryById(categoryId);
        if (category == null) {
            throw new IllegalArgumentException("Category not found by categoryId");
        }
        List<GuildChannel> channels = category.getChannels();
        if (channels.isEmpty()) {
            throw new IllegalArgumentException("Category not contains any channels");
        }
        return "Retrieved " + channels.size() + " channels:\n" +
                channels.stream()
                        .map(c -> "- " + c.getType().name() + " channel: " + c.getName() + " (ID: " + c.getId() + ")")
                        .collect(Collectors.joining("\n"));
    }
}