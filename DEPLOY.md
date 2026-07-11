# Deploying KLRAMS with Docker

Everything the app needs is in this repo. Secrets live only in a local `.env`.

## 1. Configure

```bash
cp .env.example .env
# edit .env — set strong POSTGRES_PASSWORD and ADMIN_PASSWORD
```

`POSTGRES_DB` must be the name you restore the database dump into.

## 2. Start the database, then restore the dump

```bash
docker compose up -d db

# copy the dump into the db container and restore it
docker compose cp rmms_20260710.dump db:/tmp/rmms.dump
docker compose exec db pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
    --clean --if-exists --no-owner /tmp/rmms.dump
```

(The `postgis/postgis` image already has the PostGIS extension available; the
app also runs `CREATE EXTENSION IF NOT EXISTS postgis` on startup.)

## 3. Build and start the app

```bash
docker compose up -d --build app
docker compose logs -f app        # watch for "Map caches warmed" on startup
```

The app listens on `:8090`.

## 4. Front it with nginx

Use `deploy/nginx-klrams.conf.sample`. The critical line is
`client_max_body_size 2048M;` — without it nginx returns **413** on video
uploads (its default limit is 1 MB).

## Redeploying after a code change

> A plain `git pull` does NOT update a running container — the jar is baked
> into the image. You must rebuild:

```bash
git pull
docker compose up -d --build app
```

Uploaded videos and data are safe across rebuilds because `/opt/klrams/data`
is a named volume (`klrams-data`), not part of the image.
