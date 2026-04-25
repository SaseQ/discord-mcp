package dev.saseq.services;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.attribute.IPostContainer;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.ForumChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.channel.forums.ForumPost;
import net.dv8tion.jda.api.entities.channel.forums.ForumTag;
import net.dv8tion.jda.api.entities.channel.forums.ForumTagSnowflake;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ForumService {

    private final JDA jda;

    @Value("${DISCORD_GUILD_ID:}")
    private String defaultGuildId;

    public ForumService(JDA jda) {
        this.jda = jda;
    }

    private Guild getGuild(String guildId) {
        if ((guildId == null || guildId.isEmpty()) && defaultGuildId != null && !defaultGuildId.isEmpty()) {
            guildId = defaultGuildId;
        }
        if (guildId == null || guildId.isEmpty()) throw new IllegalArgumentException("guildId cannot be null");
        Guild guild = jda.getGuildById(guildId);
        if (guild == null) throw new IllegalArgumentException("Discord server not found by guildId");
        return guild;
    }

    private ForumChannel resolveForumChannel(Guild guild, String channelId) {
        if (channelId == null || channelId.isEmpty()) throw new IllegalArgumentException("channelId cannot be null");
        GuildChannel channel = guild.getGuildChannelById(channelId);
        if (!(channel instanceof ForumChannel forum)) {
            throw new IllegalArgumentException("Channel not found or is not a forum channel");
        }
        return forum;
    }

    private ThreadChannel retrieveThreadChannelById(Guild guild, String postId) {
        ThreadChannel thread = guild.getThreadChannelById(postId);
        if (thread != null) {
            return thread;
        }
        throw new IllegalArgumentException("Forum post not found by postId");
    }

    @Tool(name = "create_forum_channel", description = "Create a new forum channel")
    public String createForumChannel(@ToolParam(description = "Discord server ID", required = false) String guildId,
                                     @ToolParam(description = "Channel name") String name,
                                     @ToolParam(description = "Category ID", required = false) String categoryId,
                                     @ToolParam(description = "Default post guidelines / topic", required = false) String topic,
                                     @ToolParam(description = "Whether channel is NSFW", required = false) String nsfw,
                                     @ToolParam(description = "Default slowmode for new posts in seconds (0-21600)", required = false) String slowmode,
                                     @ToolParam(description = "Position in channel list", required = false) String position) {
        Guild guild = getGuild(guildId);
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("name cannot be null");
        var action = guild.createForumChannel(name);
        if (categoryId != null && !categoryId.isEmpty()) {
            Category category = guild.getCategoryById(categoryId);
            if (category == null) throw new IllegalArgumentException("Category not found by categoryId");
            action.setParent(category);
        }
        if (topic != null && !topic.isEmpty()) action.setTopic(topic);
        if (nsfw != null && !nsfw.isEmpty()) action.setNSFW(Boolean.parseBoolean(nsfw));
        if (slowmode != null && !slowmode.isEmpty()) action.setSlowmode(Integer.parseInt(slowmode));
        if (position != null && !position.isEmpty()) action.setPosition(Integer.parseInt(position));
        ForumChannel forum = action.complete();
        return "Created forum channel: " + forum.getName() + " (ID: " + forum.getId() + ")";
    }

    @Tool(name = "edit_forum_channel", description = "Edit settings of a forum channel (name, topic, nsfw, slowmode, category, position, default sort, default layout)")
    public String editForumChannel(@ToolParam(description = "Discord server ID", required = false) String guildId,
                                   @ToolParam(description = "Forum channel ID") String channelId,
                                   @ToolParam(description = "New channel name", required = false) String name,
                                   @ToolParam(description = "New channel topic / post guidelines", required = false) String topic,
                                   @ToolParam(description = "Whether channel is NSFW", required = false) String nsfw,
                                   @ToolParam(description = "Default slowmode for new posts in seconds (0-21600)", required = false) String slowmode,
                                   @ToolParam(description = "Category ID (empty to remove from category)", required = false) String categoryId,
                                   @ToolParam(description = "Position in channel list", required = false) String position,
                                   @ToolParam(description = "Default sort order: RECENT_ACTIVITY or CREATION_TIME", required = false) String defaultSort,
                                   @ToolParam(description = "Default layout: LIST_VIEW or GALLERY_VIEW", required = false) String defaultLayout,
                                   @ToolParam(description = "Reason for audit log", required = false) String reason) {
        Guild guild = getGuild(guildId);
        ForumChannel forum = resolveForumChannel(guild, channelId);
        boolean hasEditableField = (name != null && !name.isEmpty())
                || topic != null
                || (nsfw != null && !nsfw.isEmpty())
                || (slowmode != null && !slowmode.isEmpty())
                || categoryId != null
                || (position != null && !position.isEmpty())
                || (defaultSort != null && !defaultSort.isEmpty())
                || (defaultLayout != null && !defaultLayout.isEmpty());
        if (!hasEditableField) {
            throw new IllegalArgumentException("At least one forum channel field must be provided");
        }
        var manager = forum.getManager();
        if (name != null && !name.isEmpty()) manager.setName(name);
        if (topic != null) manager.setTopic(topic);
        if (nsfw != null && !nsfw.isEmpty()) manager.setNSFW(Boolean.parseBoolean(nsfw));
        if (slowmode != null && !slowmode.isEmpty()) manager.setSlowmode(Integer.parseInt(slowmode));
        if (categoryId != null) {
            if (categoryId.isEmpty()) manager.setParent(null);
            else {
                Category category = guild.getCategoryById(categoryId);
                if (category == null) throw new IllegalArgumentException("Category not found by categoryId");
                manager.setParent(category);
            }
        }
        if (position != null && !position.isEmpty()) manager.setPosition(Integer.parseInt(position));
        if (defaultSort != null && !defaultSort.isEmpty()) {
            try {
                manager.setDefaultSortOrder(IPostContainer.SortOrder.valueOf(defaultSort.toUpperCase()));
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("Invalid defaultSort. Use RECENT_ACTIVITY or CREATION_TIME");
            }
        }
        if (defaultLayout != null && !defaultLayout.isEmpty()) {
            try {
                manager.setDefaultLayout(ForumChannel.Layout.valueOf(defaultLayout.toUpperCase()));
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("Invalid defaultLayout. Use LIST_VIEW or GALLERY_VIEW");
            }
        }
        if (reason != null && !reason.isEmpty()) manager.reason(reason);
        manager.complete();
        return "Updated forum channel: " + forum.getName() + " (ID: " + forum.getId() + ")";
    }

    @Tool(name = "list_forum_channels", description = "List all forum channels in the server")
    public String listForumChannels(@ToolParam(description = "Discord server ID", required = false) String guildId) {
        Guild guild = getGuild(guildId);
        List<ForumChannel> forums = guild.getForumChannels();
        if (forums.isEmpty()) return "No forum channels found in the server.";
        return "Retrieved " + forums.size() + " forum channels:\n" +
                forums.stream()
                        .map(f -> {
                            String parent = f.getParentCategory() != null ? " [" + f.getParentCategory().getName() + "]" : "";
                            return "- " + f.getName() + " (ID: " + f.getId() + ")" + parent;
                        })
                        .collect(Collectors.joining("\n"));
    }

    @Tool(name = "get_forum_channel_info", description = "Get detailed information about a forum channel including tags and settings")
    public String getForumChannelInfo(@ToolParam(description = "Discord server ID", required = false) String guildId,
                                      @ToolParam(description = "Forum channel ID") String channelId) {
        Guild guild = getGuild(guildId);
        ForumChannel forum = resolveForumChannel(guild, channelId);
        StringBuilder info = new StringBuilder()
                .append("Name: ").append(forum.getName()).append("\n")
                .append("ID: ").append(forum.getId()).append("\n")
                .append("Topic: ").append(forum.getTopic() != null ? forum.getTopic() : "none").append("\n")
                .append("NSFW: ").append(forum.isNSFW()).append("\n")
                .append("Slowmode: ").append(forum.getSlowmode()).append("s\n")
                .append("Position: ").append(forum.getPosition()).append("\n")
                .append("Default Sort: ").append(forum.getDefaultSortOrder().name()).append("\n")
                .append("Default Layout: ").append(forum.getDefaultLayout().name()).append("\n");
        if (forum.getParentCategory() != null) {
            info.append("Category: ").append(forum.getParentCategory().getName())
                    .append(" (ID: ").append(forum.getParentCategory().getId()).append(")\n");
        }
        List<ForumTag> tags = forum.getAvailableTags();
        info.append("Tags: ").append(tags.isEmpty() ? "none" : "").append("\n");
        for (ForumTag tag : tags) {
            info.append("  - ").append(tag.getName()).append(" (ID: ").append(tag.getId()).append(")")
                    .append(tag.isModerated() ? " [moderated]" : "").append("\n");
        }
        info.append("Active Posts: ").append(forum.getThreadChannels().size()).append("\n");
        info.append("Created: ").append(forum.getTimeCreated().toLocalDate());
        return info.toString();
    }

    @Tool(name = "list_forum_tags", description = "List all available tags in a forum channel")
    public String listForumTags(@ToolParam(description = "Discord server ID", required = false) String guildId,
                                @ToolParam(description = "Forum channel ID") String channelId) {
        Guild guild = getGuild(guildId);
        ForumChannel forum = resolveForumChannel(guild, channelId);
        List<ForumTag> tags = forum.getAvailableTags();
        if (tags.isEmpty()) return "No tags configured on forum: " + forum.getName();
        return "Retrieved " + tags.size() + " tags on forum " + forum.getName() + ":\n" +
                tags.stream()
                        .map(t -> "- " + t.getName() + " (ID: " + t.getId() + ")"
                                + (t.isModerated() ? " [moderated]" : "")
                                + (t.getEmoji() != null ? " " + t.getEmoji().getFormatted() : ""))
                        .collect(Collectors.joining("\n"));
    }

    @Tool(name = "create_forum_post", description = "Create a new forum post (thread) with an initial message in a forum channel")
    public String createForumPost(@ToolParam(description = "Discord server ID", required = false) String guildId,
                                  @ToolParam(description = "Forum channel ID") String channelId,
                                  @ToolParam(description = "Post title") String title,
                                  @ToolParam(description = "Content of the initial message") String message,
                                  @ToolParam(description = "Comma-separated tag IDs to apply", required = false) String tagIds) {
        Guild guild = getGuild(guildId);
        ForumChannel forum = resolveForumChannel(guild, channelId);
        if (title == null || title.isEmpty()) throw new IllegalArgumentException("title cannot be null");
        if (message == null || message.isEmpty()) throw new IllegalArgumentException("message cannot be null");

        var action = forum.createForumPost(title, MessageCreateData.fromContent(message));
        if (tagIds != null && !tagIds.isEmpty()) {
            List<ForumTagSnowflake> tags = new ArrayList<>();
            for (String raw : tagIds.split(",")) {
                String id = raw.trim();
                if (id.isEmpty()) continue;
                tags.add(ForumTagSnowflake.fromId(id));
            }
            if (!tags.isEmpty()) action.setTags(tags);
        }
        ForumPost post = action.complete();
        ThreadChannel thread = post.getThreadChannel();
        return "Created forum post: " + thread.getName() + " (ID: " + thread.getId()
                + ") in forum " + forum.getName();
    }

    @Tool(name = "list_forum_posts", description = "List active posts (threads) in a forum channel")
    public String listForumPosts(@ToolParam(description = "Discord server ID", required = false) String guildId,
                                 @ToolParam(description = "Forum channel ID") String channelId) {
        Guild guild = getGuild(guildId);
        ForumChannel forum = resolveForumChannel(guild, channelId);
        List<ThreadChannel> posts = forum.getThreadChannels();
        if (posts.isEmpty()) return "No active posts found in forum: " + forum.getName();
        return "Retrieved " + posts.size() + " active posts in forum " + forum.getName() + ":\n" +
                posts.stream()
                        .map(p -> {
                            String tagNames = p.getAppliedTags().stream()
                                    .map(ForumTag::getName)
                                    .collect(Collectors.joining(", "));
                            String tagPart = tagNames.isEmpty() ? "" : " [" + tagNames + "]";
                            String locked = p.isLocked() ? " (locked)" : "";
                            String archived = p.isArchived() ? " (archived)" : "";
                            return "- " + p.getName() + " (ID: " + p.getId() + ")" + tagPart + locked + archived;
                        })
                        .collect(Collectors.joining("\n"));
    }

    @Tool(name = "modify_forum_post", description = "Modify a forum post: lock/unlock, archive/unarchive, pin/unpin, or change applied tags")
    public String modifyForumPost(@ToolParam(description = "Discord server ID", required = false) String guildId,
                                  @ToolParam(description = "Forum post (thread) ID") String postId,
                                  @ToolParam(description = "Lock state (true/false)", required = false) String locked,
                                  @ToolParam(description = "Archive state (true/false)", required = false) String archived,
                                  @ToolParam(description = "Pin state (true/false)", required = false) String pinned,
                                  @ToolParam(description = "Comma-separated tag IDs to set (empty string to clear)", required = false) String tagIds,
                                  @ToolParam(description = "Reason for audit log", required = false) String reason) {
        Guild guild = getGuild(guildId);
        if (postId == null || postId.isEmpty()) throw new IllegalArgumentException("postId cannot be null");
        ThreadChannel thread = retrieveThreadChannelById(guild, postId);
        if (!(thread.getParentChannel() instanceof ForumChannel)) {
            throw new IllegalArgumentException("Thread is not inside a forum channel");
        }

        if (locked == null && archived == null && pinned == null && tagIds == null) {
            throw new IllegalArgumentException("At least one of locked, archived, pinned, or tagIds must be provided");
        }

        var manager = thread.getManager();
        if (locked != null && !locked.isEmpty()) manager.setLocked(Boolean.parseBoolean(locked));
        if (archived != null && !archived.isEmpty()) manager.setArchived(Boolean.parseBoolean(archived));
        if (pinned != null && !pinned.isEmpty()) manager.setPinned(Boolean.parseBoolean(pinned));
        if (tagIds != null) {
            List<ForumTagSnowflake> tags = new ArrayList<>();
            if (!tagIds.isEmpty()) {
                for (String raw : tagIds.split(",")) {
                    String id = raw.trim();
                    if (id.isEmpty()) continue;
                    tags.add(ForumTagSnowflake.fromId(id));
                }
            }
            manager.setAppliedTags(tags);
        }
        if (reason != null && !reason.isEmpty()) manager.reason(reason);
        manager.complete();
        return "Updated forum post: " + thread.getName() + " (ID: " + thread.getId() + ")";
    }
}
