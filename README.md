![wide version of the glacier logo. A mamooth in front of a glacier.](assets/glacier_logo_wide.png)

# Glacier

*A mastodon only social wall.*

With **glacier** you can follow hashtags to see the interaction of participants of an event in realtime.

This project is Open Source under the [MIT License](LICENSE) except for the following files:
- The font [Jugger Rock](frontend/src/assets/juggerrock.ttf), which is from Dan Zadorozny and may not be used for commercial purposes without a [license](https://www.iconian.com/commercial.html).
- The [Mastodon Logo](frontend/src/assets/mastodon.svg), which is from [Wikimedia](https://commons.wikimedia.org/wiki/File:Font_Awesome_5_brands_mastodon.svg)
and is under the [CC BY 4.0 Deed license](https://creativecommons.org/licenses/by/4.0/).

## Try it

### Demo

A complete free usable test installation of **glacier** will be available soon under [glacier.seism0saurus.de](https://glacier.seism0saurus.de).

### Container Image

A container image with latest stable version is available: [ghcr.io/seism0saurus/glacier:main](ghcr.io/seism0saurus/glacier:main)

You can run it locally with docker or other compatible container runtimes.
Four environment variables are needed.

#### INSTANCE

The instance is the mastodon instance with your account. You need an account to connect to the fediverse via the mastodon API.
For example *infosec.exchange* or *botsin.space*.

#### HANDLE

The handle is the mastodon handle of your account. The handle consists of your account name and the mastodon instance.
For example *glacier@botsin.space*.

#### ACCESS_KEY

The access key for your mastodon account.
Go to your mastodon instance into the account settings and select applications.
Create a new application with read access.

#### MY_DOMAIN

The domain of your personal Glacier installation.
If run locally use your hostname or localhost.


#### Docker

```bash
docker run -ti -e ACCESS_KEY=my-secret-mastodon-api-key -e HANDLE=my-mastodon-handle -e INSTANCE=my-mastodon-instance -e MY_DOMAIN=localhost:8080 -p 8080:8080 ghcr.io/seism0saurus/glacier:main
```

#### Container with nerdctl

```bash
nerdctl run -ti -e ACCESS_KEY=my-secret-mastodon-api-key -e HANDLE=my-mastodon-handle -e INSTANCE=my-mastodon-instance -e MY_DOMAIN=localhost:8080 -p 8080:8080 ghcr.io/seism0saurus/glacier:main
```

## Built it

Glacier is built with maven.
To do a complete install including frontend and backend build and tests, run the following command.
```bash
maven install
```

To create a container image for Docker or other engines,
copy the created jar into the [containerimage](./containerimage) folder.
Then change into the folder and run docker build.
```bash
cd containerimage
docker build -t glacier --build-arg JAR_FILE=name-of-the-created-jar .
```

For mor details you can look into the [pipeline](.github/workflows/build-and-deploy.yml).