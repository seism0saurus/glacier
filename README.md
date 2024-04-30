![wide version of the glacier logo. A mamooth in front of a glacier.](assets/glacier_logo_wide.png)

# Glacier

*A mastodon only social wall.*

With **Glacier** you can follow hashtags to see the interaction of participants of an event in realtime.

## Content

- [Licences](#licences)
- [Try it](#try-it)
  - [Demo](#demo)
- [Build it](#build-it)
  - [Requirements](#requirements)
    - [Build Jar](#build-jar)
    - [Build container image](#build-container-image)
      - [Docker](#docker)
      - [Buildah](#buildah)
- [Run it](#run-it)
  - [Container Image](#container-image)
    - [INSTANCE](#instance)
    - [HANDLE](#handle)
    - [ACCESS_KEY](#access_key)
    - [MY_DOMAIN](#my_domain)
    - [Docker](#docker-1)
    - [Containerd with nerdctl](#containerd-with-nerdctl)
- [Use it](#use-it)
  - [Create a Glacier Wall](#create-a-glacier-wall)
  - [Add toots](#add-toots)
- [Concepts and wordings](#concepts-and-wordings)
- [Known issues or limitations](#known-issues-or-limitations)

## Licences

This project is Open Source under the [MIT License](LICENSE) except for the following files:
- The font [Jugger Rock](frontend/src/assets/juggerrock.ttf), which is from Dan Zadorozny and may not be used for commercial purposes without a [license](https://www.iconian.com/commercial.html).
- The [Mastodon Logo](frontend/src/assets/mastodon.svg), which is from [Wikimedia](https://commons.wikimedia.org/wiki/File:Font_Awesome_5_brands_mastodon.svg)
and is under the [CC BY 4.0 Deed license](https://creativecommons.org/licenses/by/4.0/).

## Try it

### Demo

A complete free usable test installation of **glacier** will be available soon under [glacier.seism0saurus.de](https://glacier.seism0saurus.de).

The demo page always contains the last stable version from the main branch.

[![Build and deploy](https://github.com/seism0saurus/glacier/actions/workflows/build-and-deploy.yml/badge.svg)](https://github.com/seism0saurus/glacier/actions/workflows/build-and-deploy.yml)

## Build it

### Requirements

Glacier is built with maven and based on Java 21 and typescript.
To execute the built or run Glacier locally you need a Java 21 JDK.
A JRE is not sufficient for build. I recommend [Temurin](https://adoptium.net/de/temurin/releases/).

### Build Jar

To checkout the code and do a complete install including frontend and backend build and tests, run the following commands.
```bash
git clone git@github.com:seism0saurus/glacier.git
cd glacier
./mvnw clean install
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


## Use it

### Create a Glacier Wall

To create a new Glacier Wall follow these steps.

1) Go to your Glacier instance. E.g. [glacier.seism0saurus.de](https://glacier.seism0saurus.de).
2) Click into the field *Followed hashtags*
3) Enter a hashtag you want to follow and press *enter*. Repeat this for each hashtag you want to follow
4) For better usage of space, enable the full screen mode with *F11* or the menu of your web browser

### Add toots

To add a toot to your Glacier Wall follow these steps.

1) Start a new toot
2) Mention the bot of your Glacier instance in your toot. For example @glacier@botsin.space. 
This is important since not all toots with a hashtag reach the bot, which collects the toots.
This is due to the concept of federation in the Fediverse.
3) Use one of the hashtags of your Glacier Wall. For example #flowers.
This is needed, so that multiple Glacier Walls can be used with the same mastodon bot.
Only visible toots with a hashtag are shown on a Glacier Wall.
4) Post your toot. The toot appears on the Glacier Wall shortly after you've posted it

Here is an example toot:
```
@glacier@botsin.space

Hi,
very cool project. Thanks for developing a social wall ;)

#foss #opensource #mastodon
```

Here is a short video of adding a toot to the wall.
![An animated gif of the usage of Glacier. Mention the bot of your Glacier instance, write your toot, use one of the hashtags of your Glacier Wall, post. Your toot appears on the Glacier Wall](glacier.gif)


## Concepts and wordings

- *Glacier instance*: A Glacier instance is one deployment of the Glacier web application.
One instance can serve multiple Glacier Walls with different hashtags for different users.
For example glacier.seism0saurus.de is an instance of Glacier.
I deploy the current version from the main branch on that server
- *Glacier Wall*: A Glacier Wall is a single social wall for a user and is delivered by a Glacier instance.
The Glacier Wall is bound to your web browser and a Glacier instance.
For example, if you open [glacier.seism0saurs.de](https://glacier.seism0saurus.de) you get your personal Glacier Wall for testing.
If you open it in another web browser you get a second Glacier Wall with different hashtags and toots.
But both Glacier Walls run on the same Glacier instance from me but are separate Glacier Walls
- *Toot*: A toot is a post on mastodon. Glacier can show other types of posts from systems connected to the fediverse, too.
But for simplicity I speak about toots, since mastodon is the main focus

## Known issues or limitations

- The Glacier Wall needs some seconds after a new toot is added to render everything.
That's a problem I have with the Angular change detection. The toots are rendered as iframes,
because I don't want to reimplement the styling of the different fediverse servers.
But after a toot is moved to another column, the browser reloads it completely.
This takes some time to rebuild the page
- If server shows a cookie consent banner or other banners at the bottom of the screen,
the banner can block the view to the content
- Content warnings in mastodon can block the content on the Glacier Wall
- Some mastodon instances have corrupt or wrong headers for the content security policy.
Therefore, I am not allowed to load the toots from these servers as iframe.
If your toots are not shown but publicly visible, mention the bot and have a hashtag,
please open an issue. I will try to contact your instance administrator to get an exception
- Toots without mentioning the bot of the Glacier instance are not shown.
Following a hashtag is limited in the fediverse.
If a toot with a hashtag is never federated to the server with my bot, the bot never sees the toot and can't show it on the Glacier Wall.
Therefore, you have to mention the bot to make sure, the message reaches it
- Private or other restricted messages are not shown.
Well, this isn't really an issue, since you don't want to make the toot public, it shouldn't be on a Glacier Wall, too