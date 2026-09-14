-- Brings every category to the same curated source count (6, matching
-- World News after V11) instead of the uneven counts V11 left behind
-- (World News 6, Technology 4, Business & Finance 4, Science 2). Each URL
-- live-verified as a working RSS/Atom feed before this migration was written.
INSERT INTO sources (category_id, name, feed_url) VALUES
    ((SELECT id FROM categories WHERE name = 'Technology'), 'Engadget', 'https://www.engadget.com/rss.xml'),
    ((SELECT id FROM categories WHERE name = 'Technology'), 'The Register', 'https://www.theregister.com/headlines.atom'),
    ((SELECT id FROM categories WHERE name = 'Business & Finance'), 'Business Insider', 'https://www.businessinsider.com/rss'),
    ((SELECT id FROM categories WHERE name = 'Business & Finance'), 'The Economist – Business', 'https://www.economist.com/business/rss.xml'),
    ((SELECT id FROM categories WHERE name = 'Science'), 'NASA Breaking News', 'https://www.nasa.gov/rss/dyn/breaking_news.rss'),
    ((SELECT id FROM categories WHERE name = 'Science'), 'Live Science', 'https://www.livescience.com/feeds/all'),
    ((SELECT id FROM categories WHERE name = 'Science'), 'Space.com', 'https://www.space.com/feeds/all'),
    ((SELECT id FROM categories WHERE name = 'Science'), 'Phys.org', 'https://phys.org/rss-feed/');
