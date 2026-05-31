-- Voice Calendar minimal PostgreSQL schema.
-- Run this file after creating the voice_calendar database and user.

CREATE TABLE IF NOT EXISTS app_users (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(60) NOT NULL,
    password_hash VARCHAR(120) NOT NULL,
    display_name VARCHAR(60),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_app_users_username
    ON app_users (username);

CREATE TABLE IF NOT EXISTS calendar_events (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT,
    title VARCHAR(120) NOT NULL,
    start_time TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    end_time TIMESTAMP WITHOUT TIME ZONE,
    location VARCHAR(120),
    description VARCHAR(1000),
    tag VARCHAR(40) NOT NULL,
    reminder_time TIMESTAMP WITHOUT TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_calendar_events_user_start_time
    ON calendar_events (user_id, start_time);

CREATE TABLE IF NOT EXISTS recurring_events (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    title VARCHAR(120) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    start_time TIME WITHOUT TIME ZONE NOT NULL,
    end_time TIME WITHOUT TIME ZONE,
    recurrence_type VARCHAR(20) NOT NULL,
    interval_value INTEGER NOT NULL,
    days_of_week VARCHAR(80),
    location VARCHAR(120),
    description VARCHAR(1000),
    tag VARCHAR(40) NOT NULL,
    reminder_time TIME WITHOUT TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_recurring_events_user_range
    ON recurring_events (user_id, start_date, end_date);
