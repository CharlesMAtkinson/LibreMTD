# LibreMTD

## Table of Contents

- [Overview](#overview)
- [What LibreMTD Supports](#what-libremtd-supports)
- [Planned Features](#planned-features)
- [Disclaimer](#disclaimer)
- [Screenshots](#screenshots)
    - [Login Screen and Themes](#login-screen-and-themes)
    - [File](#file)
    - [Help](#help)
    - [Enter Data](#enter-data)
    - [Settings and Connect](#settings-and-connect)
    - [Submissions](#submissions)
- [Development](#development)
    - [Pre-requisites](#pre-requisites)
    - [HMRC Setup](#hmrc-setup)
        - [Application](#application)
        - [Test User](#test-user)
        - [Business ID](#business-id)
    - [Automatic Logon](#automatic-logon)
    - [Files](#files)
    - [UI Text Copy](#ui-text-copy)
    - [Known Warnings](#known-warnings)
- [License](#license)

---

## Overview

LibreMTD is pre-production free and open-source software (FOSS) — a Linux desktop
application for submitting quarterly updates and end-of-period statements to HMRC
under Making Tax Digital for Income Tax Self Assessment (MTD ITSA).

It is intended for individuals, not agents.

*Pre-production* means LibreMTD has been tested against HMRC's sandbox. The next
step is to apply to HMRC for production use.  The procedure is at
https://developer.service.hmrc.gov.uk/guides/income-tax-mtd-end-to-end-service-guide/documentation/how-to-integrate.html
in section "Process for being granted Production access".

LibreMTD was created because no FOSS application for Linux was listed at
[Choose the right software for Making Tax Digital for Income Tax](https://www.gov.uk/guidance/choose-the-right-software-for-making-tax-digital-for-income-tax).

---

## What LibreMTD Supports

- **Property income and expenses** — record rental income and allowable expenses for
  furnished and unfurnished UK residential lettings, including furnished holiday
  lettings. The property income allowance and rent-a-room schemes are not currently
  supported.
- **Dividend income** — record dividends received from UK and overseas companies.
- **Savings income** — record interest from UK bank and building society accounts.
- **Submissions** — submit quarterly updates for 6 April to 5 April tax years and
  the final declaration directly to HMRC.

---

## Planned Features

- Charitable giving
- Pension income
- Export to PDF

---

## Disclaimer

LibreMTD is independent software. It is not affiliated with, endorsed by, or
supported by HMRC. Tax rules change — always verify your figures and submission
obligations against current guidance on [gov.uk](https://www.gov.uk). LibreMTD
does not provide tax advice.

---

## Screenshots

### Login Screen and Themes

LibreMTD defaults to a green theme which harmonises with the login screen.

![Login screen](https://github.com/user-attachments/assets/15fc6c5b-3b71-4d5b-8f35-14947633a99d)

![Main window — green theme](https://github.com/user-attachments/assets/0ff82583-2915-4960-aa10-df40ce8756ff)

Light and dark themes are also available:

![Main window — light theme](https://github.com/user-attachments/assets/205acd8b-b47f-49bc-aa1c-80d21b8b5a48)

![Main window — dark theme](https://github.com/user-attachments/assets/039af4e6-445e-4656-b2a1-281c1393783b)

### File

**Export to .xlsx spreadsheet**

![Export pane](https://github.com/user-attachments/assets/8b00fec7-bc3c-4349-b929-be473b8bcc07)

**Import from .xlsx spreadsheet**

![Import pane](https://github.com/user-attachments/assets/f2ad335a-d21d-4b9a-b0b9-78e2fd5d9a55)

### Help

**LibreMTD Help**

![Help pane](https://github.com/user-attachments/assets/d3e13b06-ef49-4fbc-ad06-2cc2d92c634a)

**HMRC Links**

![HMRC links pane](https://github.com/user-attachments/assets/3b942e67-b7ce-44bd-af82-714621336e68)

### Enter Data

**Tax Summary**

![Tax Summary pane](https://github.com/user-attachments/assets/f68612b9-4293-49c1-881a-cca300ff2709)

**Income — Dividends**

![Dividend income pane](https://github.com/user-attachments/assets/7910f097-0715-4566-9981-96f889cdc9bf)
![Dividend income pane](https://github.com/user-attachments/assets/af2403dc-5804-457f-ba28-22c584a4378e)
![Dividend income pane](https://github.com/user-attachments/assets/3864f537-22e6-4741-b28a-bc934a11da5f)

**Income — Property**

![Property income pane](https://github.com/user-attachments/assets/52360fbd-bc70-48bf-9556-2b01433d47d7)

**Income — Savings**

![Savings income pane](https://github.com/user-attachments/assets/ac07a193-9593-469c-8073-8b27e2923a13)

**Expenses**

![Expenses pane](https://github.com/user-attachments/assets/66f0ac70-390b-4fca-9cc9-4b43fec537f2)

**Properties**

![Properties pane](https://github.com/user-attachments/assets/eb3ce36f-ad25-4822-b049-d8b2e0af1d69)

### HNRC

**Settings**

![Settings pane](https://github.com/user-attachments/assets/53863c23-11e7-4998-90af-46a58b10ec42)

**Connect**

![Connect pane](https://github.com/user-attachments/assets/6d34efd8-8788-4b7a-a6b9-21be012f5317)
![Connect pane](https://github.com/user-attachments/assets/ab9a8a72-6cf6-4a7c-bac5-c1e8859d3f9f)

### Submissions

![Submissions pane](https://github.com/user-attachments/assets/95bb9f69-9b2f-47d1-9d85-3b9b75deb7f2)

---

## Development

Because LibreMTD is pre-production software, it can currently only be used for
development and testing against HMRC's sandbox.

This was my first Kotlin/JavaFX project. Collaboration is welcome across all areas:
programming, testing, and documentation.

### Pre-requisites

Here's what I used. Any IDE supporting Kotlin and Gradle should work.

| Component | Version used                             |
|-----------|------------------------------------------|
| OS | Debian Trixie                            |
| JDK | OpenJDK 21.0.11                          |
| SQLite | 3.46.1 (via Exposed)                     |
| IDE | IntelliJ IDEA 2025.3.4 Community edition |

Clone the repository. The Gradle wrapper (`gradlew`) should
download all other dependencies.

### HMRC Setup

#### Application

HMRC requires each developer to register on the Developer Hub and obtain their own application
sandbox credentials.

1. Log in at <https://developer.service.hmrc.gov.uk>.
2. Open the **Applications** tab.
3. Click **Add an application to the sandbox**.
4. Enter a name of your choice when prompted.
5. Subscribe to the APIs you need. The following list is what LibreMTD was
   developed against — it is broader than strictly required, but choosing extras
   causes no harm beyond occasional API-change notification emails from HMRC:

   | API | Version |
      |-----|---------|
   | Individual Tax | 1.1 |
   | Individuals Savings Income (MTD) | 2.0 |
   | Business Details (MTD) | 2.0 |
   | Other Deductions (MTD) | 2.0 |
   | Test Fraud Prevention Headers | 1.0 |
   | Self Assessment Accounts (MTD) | 4.0 |
   | Obligations (MTD) | 3.0 |
   | Individuals Pensions Income (MTD) | 2.0 |
   | Self Assessment Individual Details (MTD) | 2.0 |
   | Self Assessment Assist (MTD) | 1.0 |
   | National Insurance Test Support | 1.0 |
   | Property Business (MTD) | 6.0 |
   | National Insurance | 1.1 |
   | Individual Income | 1.2 |
   | Individual Calculations (MTD) | 8.0 |
   | Hello World | 1.0 |
   | Self Assessment Test Support (MTD) | 1.0 |
   | Create Test User | 1.0 |
   | Individuals State Benefits (MTD) | 2.0 |

6. Click **View application credentials** and note your **Client ID**.
7. Click **Generate a client secret** and note it immediately — it is not shown again.
8. Add redirect URI: `http://localhost:8080/oauth/callback`

#### Test User

Creating a test user is a two-step process.

**Step 1** — obtain an access token using your Client ID and secret:

```bash
curl -X POST \
  "https://test-api.service.hmrc.gov.uk/oauth/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials&client_id=<client_ID>&client_secret=<client_secret>"
```

**Step 2** — create the test user, substituting the access token from step 1:

```bash
curl -X POST \
  "https://test-api.service.hmrc.gov.uk/create-test-user/individuals" \
  -H "Authorization: Bearer <access_token>" \
  -H "Content-Type: application/json" \
  -H "Accept: application/vnd.hmrc.1.0+json" \
  -d '{
    "serviceNames": [
      "mtd-income-tax",
      "self-assessment"
    ]
  }'
```

The response includes a `userId`, `password`, `nino` (National Insurance number),
and other values. Note them all.

In LibreMTD's **HMRC Settings**, enter the values for which fields exist and save.

#### Business ID

In LibreMTD, use **HMRC Connect** to authenticate with the sandbox using the test
user's `userId` and `password`. Then run this command, substituting <nino> in the
URL with the test user's NINO and the 1 in "id = 1" with the LibreMTD user number.
The first registered user has "id = 1".

```bash
curl -X POST \
  "https://test-api.service.hmrc.gov.uk/individuals/self-assessment-test-support/business/<nino>" \
  -H "Authorization: Bearer $(sqlite3 ~/.local/share/LibreMTD/app.db 'SELECT hmrc_access_token FROM libremtd_users WHERE id = 1;')" \
  -H "Content-Type: application/json" \
  -H "Accept: application/vnd.hmrc.1.0+json" \
  -d '{"typeOfBusiness": "uk-property"}'
```

The response contains a `businessId`, for example:

```json
{"businessId":"X1IS13524221645"}
```

In LibreMTD's **HMRC Settings**, click **Fetch from HMRC**. The field should
populate with the business ID you just created. Save the settings.

### Automatic Login

In development mode, when using the sandbox, environment variables `DEV_PASSWORD` and 
`DEV_USERNAME` can be used to automate login. 

### Files

| Purpose | Path |
|---------|------|
| Log | `~/.local/state/LibreMTD/log/app.log` |
| Database | `~/.local/share/LibreMTD/app.db` |

### UI Text Copy

Most UI text can be copied to the clipboard by right-clicking and choosing **Copy**.

### Known Warnings

> WARNING: Unsupported JavaFX configuration: classes were loaded from
> 'unnamed module @xxxxxxxx'

This warning can be safely ignored.

---

## License

This project is licensed under the GNU General Public License v3.0 (GPLv3).

You may copy, modify, and distribute this software under the terms of the GPLv3.
A copy of the license is provided in the [`LICENSE`](LICENSE) file in this
repository. If not, you can obtain the full license text from the Free Software
Foundation: <https://www.gnu.org/licenses/gpl-3.0.txt>

SPDX-License-Identifier: GPL-3.0-only
