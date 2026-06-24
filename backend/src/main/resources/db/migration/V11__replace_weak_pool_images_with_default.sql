UPDATE pools
SET image_url = '/swimpulse-pool-shark.png'
WHERE image_url IS NOT NULL
  AND image_url <> '/swimpulse-pool-shark.png'
  AND (
      LOWER(image_url) LIKE '%favicon%'
      OR LOWER(image_url) LIKE '%.ico%'
      OR LOWER(image_url) LIKE '%apple-touch-icon%'
      OR LOWER(image_url) LIKE '%cdninstagram.com%'
      OR LOWER(image_url) LIKE '%ssl.pstatic.net/static/blog/icon%'
      OR LOWER(image_url) LIKE '%/images/icon%'
      OR LOWER(image_url) LIKE '%/img/%logo%'
  );
