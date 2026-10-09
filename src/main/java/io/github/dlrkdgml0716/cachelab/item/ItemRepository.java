package io.github.dlrkdgml0716.cachelab.item;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Repository
public class ItemRepository {
    private final JdbcTemplate jdbc;

    public ItemRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Item find(long id) {
        return jdbc.query("SELECT id, version, item_value FROM lab_item WHERE id = ?",
                (rs, row) -> new Item(rs.getLong(1), rs.getLong(2), rs.getString(3)), id)
                .stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown item"));
    }

    public Item touch(long id) {
        if (jdbc.update("UPDATE lab_item SET version = version + 1 WHERE id = ?", id) != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown item");
        }
        return find(id);
    }
}
