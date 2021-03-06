package me.twodee.friendlyneighbor.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import me.twodee.friendlyneighbor.entity.Post;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class PostData {
    public String postId;
    public String title;
    public Post.PostType type;
}
