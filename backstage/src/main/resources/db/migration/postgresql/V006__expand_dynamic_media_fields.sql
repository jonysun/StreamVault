ALTER TABLE biz_video
    ALTER COLUMN videoaddr TYPE TEXT,
    ALTER COLUMN videocover TYPE TEXT,
    ALTER COLUMN videounrealaddr TYPE TEXT,
    ALTER COLUMN originaladdress TYPE TEXT,
    ALTER COLUMN sourceurl TYPE TEXT,
    ALTER COLUMN authoravatar TYPE TEXT;

ALTER TABLE biz_graphic_content
    ALTER COLUMN markroute TYPE TEXT,
    ALTER COLUMN originaladdress TYPE TEXT,
    ALTER COLUMN sourceurl TYPE TEXT,
    ALTER COLUMN authoravatar TYPE TEXT;
