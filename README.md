# ChemE Jeopardy Web App

Browser-based ChemE Jeopardy for live classroom, review-session, and competition play. The app replaces a single-machine presentation with a server-hosted game that supports a moderator console, player buzzing, and a shared room display. The game flow and default rule assumptions are based on AIChE ChemE Jeopardy competition materials [1].

## Features

- Master game manager for creating multiple parallel game rooms.
- Per-game moderator console for setup, clue selection, judging, scoring, and Final Jeopardy.
- Player console for joining teams, clock synchronization, buzzing, wagers, and Final Jeopardy responses.
- Read-only display screen for the board, team scores, clue prompts, and answer reveals.
- Separate moderator and player join passwords per game room.
- Fairer buzz handling using client clock calibration and synchronized timestamps.
- Configurable teams, categories, clues, point values, timers, Daily Doubles, Final Jeopardy, and tie-breakers.
- Question set loading from the default JSON file or one runtime upload per game.
- Image question packages using `.chemej`/ZIP files with `game.json` and local images.
- Per-user player themes and moderator-controlled display themes.

## Project Structure

- `src/com/chemejeopardy/Main.java` - application entry point.
- `src/com/chemejeopardy/config/AppConfig.java` - environment and deployment configuration.
- `src/com/chemejeopardy/server/AppServer.java` - embedded HTTP server, routes, static assets, and SSE streams.
- `src/com/chemejeopardy/server/AuthManager.java` - moderator and player access control.
- `src/com/chemejeopardy/game/GameEngine.java` - game rules, state machine, scoring, and source-file persistence.
- `src/com/chemejeopardy/util/Json.java` - small JSON parser/stringifier.
- `public/moderator.html` - moderator UI.
- `public/player.html` - player UI.
- `public/display.html` - room display UI.
- `tools/cheme-jeopardy-question-tool.jar` - Java question authoring, CSV conversion, and image package tool.
- `data/game-definition.json` - default question set template for new game rooms.
- `Dockerfile` - optional portable container deployment.
- `.env.example` - local environment variable template.

## Requirements

Local development:

- JDK 21 or newer
- PowerShell on Windows for the optional build/run helper scripts, or any shell that can run `javac` and `java`

Docker deployment:

- Any platform that can build and run a Dockerfile
- One running instance only

## Configuration

Create a local `.env` file from `.env.example`:

```text
CHEME_MODERATOR_PASSWORD=choose-a-private-moderator-password
CHEME_GAME_FILE=data/game-definition.json
```

Environment variables:

- `CHEME_MODERATOR_PASSWORD` - required. Admin password for `/` and `/games`.
- `CHEME_GAME_FILE` - optional. Path to the default question source JSON file used when creating a game without uploading or selecting another file.
- `CHEME_PUBLIC_DIR` - optional. Path to static browser files. Defaults to `public`.
- `PORT` - optional. Cloud platforms commonly set this automatically. Defaults to `8080`.

Command-line arguments also work:

```powershell
java -cp out com.chemejeopardy.Main 8080 data/game-definition.json
```

## Question Sources

New games can start from a JSON game file. By default, that file is:

```text
data/game-definition.json
```

It includes:

- game title
- team names, colors, and active flags
- team count and max players per team
- board categories
- clue prompts
- official responses
- point values
- Daily Double flags
- Final Jeopardy category, clue, and response
- tie-breaker clues
- game timers

To use a different prepared default game template, set:

```text
CHEME_GAME_FILE=data/my-event-game.json
```

When creating a game room, the admin can either use the default question file or upload a question file directly. The moderator can also replace the question set from the moderator setup view.

Supported upload formats:

- `.json` - normal game definition file.
- `.chemej` or `.zip` - image package with `game.json` at the package root and local image files under `images/`.

Only one uploaded file is kept per game at runtime. Uploading another question file replaces the previous runtime upload for that game. These uploads live in the running server instance, not in GitHub or long-term storage.

Image clues use this JSON shape:

```json
"image": { "src": "images/pump-diagram.png", "alt": "Centrifugal pump diagram" }
```

The default `data/game-definition.json` intentionally remains image-free.

## Authoring Tool

The `tools/` folder contains one Java executable jar for preparing question sets:

- `cheme-jeopardy-question-tool.jar` - opens a local GUI for manual authoring, existing JSON editing, CSV conversion, and image package creation. It also supports command-line modes for repeatable conversions.

Open the GUI:

```powershell
java -jar tools\cheme-jeopardy-question-tool.jar
```

The GUI output folder defaults to `tmp\question-tool-output`, which is local-only and ignored by Git. The user can import an existing JSON into the manual grid, select CSV/JSON/image inputs with file pickers, and choose a different output folder.

The editable Java source for the tool can be kept locally under `tools-src\cheme-jeopardy-question-tool\`. That folder is ignored by Git, so GitHub only receives the compiled jar in `tools\`.

If you edit the local source, rebuild the jar with:

```powershell
javac -d tmp\question-tool-classes src\com\chemejeopardy\util\Json.java tools-src\cheme-jeopardy-question-tool\com\chemejeopardy\tools\QuestionTool.java
jar --create --file tools\cheme-jeopardy-question-tool.jar --main-class com.chemejeopardy.tools.QuestionTool -C tmp\question-tool-classes .
```

If `jar` is not on your PATH, run the same command with the full JDK path to `jar.exe`.

Create a CSV template:

```powershell
java -jar tools\cheme-jeopardy-question-tool.jar --write-template tmp\questions_template.csv --categories 6 --clues 5
```

Convert CSV to JSON:

```powershell
java -jar tools\cheme-jeopardy-question-tool.jar --csv-to-json tmp\questions_template.csv --out tmp\room-n.json --title "Room N"
```

Create an image package when local images are referenced:

```powershell
java -jar tools\cheme-jeopardy-question-tool.jar --package --game-json tmp\room-n.json --images-dir .\my-images --out tmp\room-n.chemej
```

Show all tool options:

```powershell
java -jar tools\cheme-jeopardy-question-tool.jar --help
```

## Build And Run Locally

Compile:

```powershell
.\build.ps1
```

Run:

```powershell
.\run.ps1
```

Run on another port:

```powershell
.\run.ps1 -Port 8090
```

Run with another game source file:

```powershell
.\run.ps1 -Port 8080 -GameFile data/my-event-game.json
```

Manual compile/run:

```powershell
$files = Get-ChildItem -Recurse src -Filter *.java | ForEach-Object { $_.FullName }
javac -d out $files
java -cp out com.chemejeopardy.Main 8080
```

## Browser URLs

When running locally:

- Admin home and game manager: `http://localhost:8080/`
- Alternate game manager URL: `http://localhost:8080/games`

No game room exists until the admin creates one. If the first room uses the default slug, its links are:

- Player UI: `http://localhost:8080/game-1/player`
- Display UI: `http://localhost:8080/game-1/display`
- Moderator UI: `http://localhost:8080/game-1/moderator`

On another device in the same network, replace `localhost` with the host computer's LAN IP.

## Docker

Docker is optional for local use, but useful for Azure Container Apps, Google Cloud Run, Railway, Render, Fly.io, and similar platforms.

Build:

```powershell
docker build -t cheme-jeopardy .
```

Run:

```powershell
docker run --rm -p 8080:8080 `
  -e CHEME_MODERATOR_PASSWORD="choose-a-private-password" `
  cheme-jeopardy
```

Open:

```text
http://localhost:8080/games
```

To keep a prepared question template outside the image, mount a data directory and set `CHEME_GAME_FILE` to that mounted path.

## Cloud Deployment Notes

Use one instance/replica. Each game room has its own in-memory state inside that instance, so multiple Container App replicas would split the same room into separate states.

Good targets:

- Azure for Students: Azure Container Apps or App Service.
- Google Cloud: Cloud Run using the Dockerfile.
- AWS: Lightsail, EC2, App Runner, or another single-container target.
- Render/Railway/Fly.io: Docker-based web service.

You do not need a custom domain. Cloud hosts provide a default HTTPS URL. A custom domain is optional and mainly makes the link nicer.

For live play, configure the host for one always-on instance when possible:

- Azure Container Apps: min replicas `1`, max replicas `1`.
- Google Cloud Run: min instances `1`, max instances `1`.

If the service scales to zero or restarts, the in-memory live game state can reset.

Set secrets in the cloud dashboard, not in GitHub:

```text
CHEME_MODERATOR_PASSWORD
```

## Moderator Flow

1. Open `/` or `/games`.
2. Log in with `CHEME_MODERATOR_PASSWORD`.
3. Create a game room with a display title, URL slug, moderator password, player password, and question set.
4. Open `/game-1/moderator` or the moderator link for the game room.
5. Log in with that game's moderator password.
6. Load or replace the JSON or `.chemej` question set if needed.
7. Edit or review settings and team names.
8. Click **Save Settings**.
9. Start the game.
10. Select clues, open buzzing, judge responses, and click **Return To Board** after answer reveals.

## Player Flow

1. Open `/game-1/player` or the player link for the game room.
2. Enter a display name, team, and player password.
3. Join the team.
4. Use the focused buzz screen during clues.
5. Expand the details panels only when you need the board, scores, or extra game state.
6. Buzz, wager, and submit Final Jeopardy responses when prompted.

## Display Flow

Open `/game-1/display` or the display link for the game room on a projector or shared screen. Before the moderator starts the game, it shows the team roster and joined-player counts. Once the game starts, it becomes the read-only board and updates automatically as the moderator controls the match.

## Manual Verification Checklist

1. Set `CHEME_MODERATOR_PASSWORD`.
2. Compile the app.
3. Start the server.
4. Open `/` or `/games` and confirm there are no games before creation.
5. Log in as admin and create `game-1`.
6. Open `/game-1/moderator`, `/game-1/player`, and `/game-1/display`.
7. Log in as moderator and confirm the player password.
8. Join a team from the player page.
9. Create `game-2` from `/games` and confirm its moderator/player URLs load independently.
10. Confirm the display lobby shows teams and joined players before the game starts.
11. Start the game.
12. Select a clue, finish reading, buzz, judge, reveal, and return to board.
13. Test Daily Double, Final Jeopardy, and tie-breaker paths if they are enabled.
14. Upload a `.json` or `.chemej` question set and confirm it replaces the previous runtime upload for that game only.

## Generative AI

Generative AI was used as an aid during the development process.

## License

This project is licensed under the MIT License. See [LICENSE](LICENSE).

## References

[1] AIChE, "ChemE Jeopardy Competition," American Institute of Chemical Engineers. Accessed: May 4, 2026. [Online]. Available: https://www.aiche.org/community/awards/cheme-jeopardy-competition
