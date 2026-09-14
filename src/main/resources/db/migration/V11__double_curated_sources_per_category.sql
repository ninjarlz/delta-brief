-- Doubles each category's curated source count (each URL live-verified as a
-- working RSS/Atom feed before this migration was written). Picked for
-- editorial/geographic diversity alongside the V6 seed, not just volume —
-- World News gains non-UK/Qatar perspectives (US, Germany, France).
INSERT INTO sources (category_id, name, feed_url) VALUES
    ((SELECT id FROM categories WHERE name = 'World News'), 'NPR World', 'https://feeds.npr.org/1004/rss.xml'),
    ((SELECT id FROM categories WHERE name = 'World News'), 'DW World', 'https://rss.dw.com/xml/rss-en-world'),
    ((SELECT id FROM categories WHERE name = 'World News'), 'France24', 'https://www.france24.com/en/rss'),
    ((SELECT id FROM categories WHERE name = 'Technology'), 'The Verge', 'https://www.theverge.com/rss/index.xml'),
    ((SELECT id FROM categories WHERE name = 'Technology'), 'Wired', 'https://www.wired.com/feed/rss'),
    ((SELECT id FROM categories WHERE name = 'Business & Finance'), 'Forbes Business', 'https://www.forbes.com/business/feed/'),
    ((SELECT id FROM categories WHERE name = 'Business & Finance'), 'Fortune', 'https://fortune.com/feed/'),
    ((SELECT id FROM categories WHERE name = 'Science'), 'Scientific American', 'https://www.scientificamerican.com/platform/syndication/rss/');
