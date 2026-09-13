INSERT INTO categories (name) VALUES
    ('World News'),
    ('Technology'),
    ('Business & Finance'),
    ('Science');

INSERT INTO sources (category_id, name, feed_url) VALUES
    ((SELECT id FROM categories WHERE name = 'World News'), 'BBC News – World', 'http://feeds.bbci.co.uk/news/world/rss.xml'),
    ((SELECT id FROM categories WHERE name = 'World News'), 'Al Jazeera', 'https://www.aljazeera.com/xml/rss/all.xml'),
    ((SELECT id FROM categories WHERE name = 'World News'), 'The Guardian – World', 'https://www.theguardian.com/world/rss'),
    ((SELECT id FROM categories WHERE name = 'Technology'), 'TechCrunch', 'https://techcrunch.com/feed/'),
    ((SELECT id FROM categories WHERE name = 'Technology'), 'Ars Technica', 'https://feeds.arstechnica.com/arstechnica/index'),
    ((SELECT id FROM categories WHERE name = 'Business & Finance'), 'CNBC – Top News', 'https://www.cnbc.com/id/100003114/device/rss/rss.html'),
    ((SELECT id FROM categories WHERE name = 'Business & Finance'), 'MarketWatch – Top Stories', 'http://feeds.marketwatch.com/marketwatch/topstories/'),
    ((SELECT id FROM categories WHERE name = 'Science'), 'ScienceDaily', 'https://www.sciencedaily.com/rss/all.xml');
