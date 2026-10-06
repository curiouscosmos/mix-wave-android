-- Repair totals previously inflated by audio likes. Safe to run again.
UPDATE discourse
SET total_likes = (
    SELECT COUNT(*) FROM discourse_likes
    WHERE discourse_likes.discourse_id = discourse.id
);
