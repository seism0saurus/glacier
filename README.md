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

The demo page always contains the last stable version from the main branch.

[![Build and deploy](https://github.com/seism0saurus/glacier/actions/workflows/build-and-deploy.yml/badge.svg)](https://github.com/seism0saurus/glacier/actions/workflows/build-and-deploy.yml)

## Built it

### Requirements

Glacier is built with maven and based on Java 21 and typescript.
To execute the built or run Glacier locally you need a Java 21 JDK.
A JRE is not sufficient for build. I recommend [Temurin](https://adoptium.net/de/temurin/releases/).

### Build Jar

To do a complete install including frontend and backend build and tests, run the following command.
```bash
maven install
```

After the build you can run Glacier locally from the commandline, to test the jar before packaging it into a container image.
```bash
ACCESS_KEY=my-secret-mastodon-api-key -e HANDLE=my-mastodon-handle -e INSTANCE=my-mastodon-instance -e MY_DOMAIN=localhost:8080 java -jar target/glacier-0.0.1-SNAPSHOT.jar
```

### Build container image

First create the jar.
To create a container image for Docker or other engines,
copy the created jar into the [containerimage](./containerimage) folder.
Then change into the folder and run docker build.
```bash
cp target/glacier-0.0.1-SNAPSHOT.jar containerimage/
cd containerimage
```

To build the image you can use Docker
or any compatible build tool that creates standard container images like [buildah](https://buildah.io/).

#### Docker

```bash
docker build -t glacier --build-arg JAR_FILE=glacier-0.0.1-SNAPSHOT.jar .
```

#### Buildah

```bash
buildah build --build-arg JAR_FILE=glacier-0.0.1-SNAPSHOT.jar  -f Dockerfile -t glacier .
```

## Run it

### Container Image

A container image with latest stable version is available: [ghcr.io/seism0saurus/glacier:main](ghcr.io/seism0saurus/glacier:main)
Or you can build your own version with the steps from Build it.

You can run the image locally with docker or other compatible container runtimes.
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
For non http ports add the port to the hostname for correct redirects and checks.
For example *localhost:8080*.
You can user Docker or any other compatible container runtime like [containerd](https://containerd.io/) with [nerdctl](https://github.com/containerd/nerdctl).

#### Docker

```bash
docker run -ti -e ACCESS_KEY=my-secret-mastodon-api-key -e HANDLE=my-mastodon-handle -e INSTANCE=my-mastodon-instance -e MY_DOMAIN=localhost:8080 -p 8080:8080 ghcr.io/seism0saurus/glacier:main
```

#### Containerd with nerdctl

```bash
nerdctl run -ti -e ACCESS_KEY=my-secret-mastodon-api-key -e HANDLE=my-mastodon-handle -e INSTANCE=my-mastodon-instance -e MY_DOMAIN=localhost:8080 -p 8080:8080 ghcr.io/seism0saurus/glacier:main
```
