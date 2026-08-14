# LibreMTD

## Table of contents

- [Overview](#overview)
    - [What LibreMTD supports](#what-libremtd-supports)
    - [Planned features](#planned-features)
    - [Disclaimer](#disclaimer)
- [Screenshots](#screenshots)
    - [Login screen and themes](#login-screen-and-themes)
    - [File](#file)
        - [Export to .xlsx spreadsheet](#export-to-xlsx-spreadsheet)
        - [Import from .xlsx spreadsheet](#import-from-xlsx-spreadsheet)
    - [Help](#help)
        - [LibreMTD Help](#libremtd-help)
        - [HMRC links](#hmrc-links)
    - [OVERVIEW](#overview-1)
        - [Tax summary](#tax-summary)
    - [INCOME](#income)
        - [Dividends](#dividends)
        - [UK property](#uk-property)
        - [Foreign property](#foreign-property)
        - [Savings](#savings)
    - [EXPENSES](#expenses)
        - [UK property](#uk-property-1)
        - [Foreign property](#foreign-property-1)
    - [PROPERTY](#property)
        - [Manage](#manage)
    - [HMRC](#hmrc)
        - [Settings](#settings)
        - [Connect](#connect)
        - [Submissions](#submissions)
- [Development](#development)
    - [Pre-requisites](#pre-requisites)
    - [HMRC setup](#hmrc-setup)
        - [Application](#application)
        - [Test user](#test-user)
        - [Business IDs](#business-ids)
    - [Automatic logon](#automatic-logon)
    - [Files](#files)
    - [UI text copy](#ui-text-copy)
    - [Warnings](#warnings)

## Overview

LibreMTD is pre-production free and open-source software (FOSS) Linux desktop application for submitting quarterly updates and end-of-period statements to HMRC under Making Tax Digital for Income Tax Self Assessment (MTD ITSA).

It is for individuals, not for agents.

"Pre-production" means LibreMTD has been tested using HMRC's sandbox. The next steps are:

- Implement the remaining items listed at HMRC's [end-to-end service guide](https://developer.service.hmrc.gov.uk/guides/income-tax-mtd-end-to-end-service-guide/documentation/how-to-integrate.html#full-end-to-end-product):
    - [Individuals Tax Liability Adjustments](https://developer.service.hmrc.gov.uk/api-documentation/docs/api/service/individuals-tax-liability-adjustments-api/1.0)
    - [Individual Calculations](https://developer.service.hmrc.gov.uk/api-documentation/docs/api/service/individual-calculations-api/8.0)
- Apply to HMRC for production use.

LibreMTD was created because no FOSS applications for Linux could be found via HMRC's [Choose the right software for Making Tax Digital for Income Tax](https://www.gov.uk/guidance/choose-the-right-software-for-making-tax-digital-for-income-tax) page.

LibreMTD's licence is GPLv3.

### What LibreMTD supports

- **Property income and expenses** — record rental income and allowable expenses for furnished and unfurnished UK property, including furnished holiday lettings, and for foreign property. The property income allowance and rent-a-room schemes are not supported.
- **Dividend income** — record dividends received from UK and foreign companies.
- **Savings income** — record interest from UK bank and building society accounts.
- **Submissions** — submit to HMRC: quarterly updates for 6 April to 5 April tax years and the final declaration with Business Source Adjustable Summary (BSAS).

### Planned features

- Charitable giving.
- Pension income.
- Export to PDF.

### Disclaimer

LibreMTD is independent software. It is not affiliated with, endorsed by, or supported by HMRC. Tax rules change — always verify your figures and submission obligations against current guidance on gov.uk. LibreMTD does not provide tax advice.

## Screenshots

### Login screen and themes

LibreMTD defaults to a green theme which harmonises with the login screen.

![Login screen](https://github.com/user-attachments/assets/1944395f-c62e-4951-bbbb-0e6242d75e0a)

![Dashboard, green theme](https://github.com/user-attachments/assets/dd4b5301-bceb-4b69-8e45-d3e25b2ce9ae)

Light and dark themes are also available:

![Dashboard, light theme](https://github.com/user-attachments/assets/9bcd861b-71f3-4d5a-b277-57464bcb1d60)

![Dashboard, dark theme](https://github.com/user-attachments/assets/56b7af4c-1fa0-4276-b8d4-c7df24d1bb3c)

### File

#### Export to .xlsx spreadsheet

![Export to spreadsheet](https://github.com/user-attachments/assets/0f942638-62bc-476b-9706-4f837a3a275c)

#### Import from .xlsx spreadsheet

![Import from spreadsheet](https://github.com/user-attachments/assets/48ba8b65-7321-4dbf-ac4b-f915496b3b12)

### Help

#### LibreMTD Help

![LibreMTD Help](https://github.com/user-attachments/assets/7d7f2b12-fca6-4ade-b22b-693f29065697)

#### HMRC links

![HMRC links](https://github.com/user-attachments/assets/936b33c0-643e-401b-95e6-e415fc393368)

### OVERVIEW

#### Tax summary

![Tax summary](https://github.com/user-attachments/assets/070408b0-039c-4a55-8ec3-333c6f704d89)

### INCOME

#### Dividends

![Dividend income](https://github.com/user-attachments/assets/5779fbba-7d9a-4091-b315-4610930a06bd)

#### UK property

![UK property income](https://github.com/user-attachments/assets/113df10d-2f1b-4acc-b222-554718154438)

#### Foreign property

![Foreign property income](https://github.com/user-attachments/assets/43ffc80f-07b2-448d-abe1-64c525eb33e8)

#### Savings

![Savings income](https://github.com/user-attachments/assets/fc8c4c37-3704-4402-8ac5-5a68fb0f028e)

### EXPENSES

#### UK property

![UK property expenses](https://github.com/user-attachments/assets/38d62ec6-b98b-4514-b00c-322c20b195f5)

#### Foreign property

![Foreign property expenses](https://github.com/user-attachments/assets/e910ee2f-e4a5-47f4-83bd-0e92aa30debf)

### PROPERTY

#### Manage

![Manage properties](https://github.com/user-attachments/assets/55ec58ee-9ae0-479b-8a2b-07c7f75f4434)

### HMRC

#### Settings

![HMRC settings](https://github.com/user-attachments/assets/e430e896-b883-4615-9b52-3cb980060a55)

#### Connect

![HMRC connect](https://github.com/user-attachments/assets/4a411315-48cf-4be3-a153-a44fc51580bb)

#### Submissions

![HMRC submissions](https://github.com/user-attachments/assets/b17a2e15-9e79-43f2-a47e-d7852382b85c)

## Development

Because LibreMTD is pre-production software, it can only be used for development and testing against HMRC's sandbox.

I am not a Kotlin or JavaFX programmer. This was my first GUI application project. I am grateful for free help from Claude Sonnet.

Collaboration is welcome in all areas of the project: programming, testing and documentation.

### Pre-requisites

LibreMTD is a Kotlin/JavaFX application. It was developed on Debian Trixie using OpenJDK 21.0.11 and SQLite 3.46.1 via Exposed.

The IDE used was IntelliJ IDEA 2025.3.4.

### HMRC setup

#### Application

- Login at [developer.service.hmrc.gov.uk](https://developer.service.hmrc.gov.uk/).
- Open the Applications tab.
- Click "Add an application to the sandbox".
- When asked "What's the name of your application?", enter your own name of choice.
- Here is the list of APIs chosen for LibreMTD. They are more than is required. AFAIK there is no cost from choosing more than is required apart from spurious emails about API changes:
    - Individual Tax 1.1
    - Individuals Savings Income (MTD) 2.0
    - Business Details (MTD) 2.0
    - Other Deductions (MTD) 2.0
    - Test Fraud Prevention Headers 1.0
    - Self Assessment Accounts (MTD) 4.0
    - Obligations (MTD) 3.0
    - Individuals Pensions Income (MTD) 2.0
    - Self Assessment Individual Details (MTD) 2.0
    - Self Assessment Assist (MTD) 1.0
    - National Insurance Test Support 1.0
    - Property Business (MTD) 6.0
    - National Insurance 1.1
    - Individual Income 1.2
    - Individual Calculations (MTD) 8.0
    - Hello World 1.0
    - Self Assessment Test Support (MTD) 1.0
    - Create Test User 1.0
    - Individuals State Benefits (MTD) 2.0
    - Business Source Adjustable Summary (MTD) 7.0
- Click "View application credentials" and continue to display the Client ID. Note it.
- Continue to "Generate a client secret". Note it.
- Add redirect URI `http://localhost:8080/oauth/callback`.

#### Test user

Creating a test user is a two-step process. In the first step, use the client ID and client secret obtained when setting up the application above:

```bash
curl -X POST \
  "https://test-api.service.hmrc.gov.uk/oauth/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials&client_id=<client_ID>&client_secret=<client_secret>"
```

That should generate an access token for use in the second-step command:

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

That should generate a userId, password, nino (National Insurance number) and other test user values. Note them.

In LibreMTD's HMRC settings, enter the ones for which fields are provided, and save.

#### Business IDs

To get a UK property business ID, open LibreMTD and use HMRC Connect to authenticate with the sandbox using the test user's userId and password, then run this command with the test user's nino in place of `<nino>`:

```bash
curl -X POST \
  "https://test-api.service.hmrc.gov.uk/individuals/self-assessment-test-support/business/<nino>" \
  -H "Authorization: Bearer $(sqlite3 ~/.local/share/LibreMTD/app.db 'SELECT hmrc_access_token FROM libremtd_users WHERE id = 1;')" \
  -H "Content-Type: application/json" \
  -H "Accept: application/vnd.hmrc.1.0+json" \
  -d '{"typeOfBusiness": "uk-property"}'
```

That should generate a businessId. Note it.

In LibreMTD's HMRC settings, click the "UK property business ID" "Fetch from HMRC". The field should be populated with the businessId just generated. Click "Save settings".

To get a "Foreign property business ID", use the same procedure except:

- Change:

  ```
  -d '{"typeOfBusiness": "uk-property"}'
  ```

  to:

  ```
  -d '{"typeOfBusiness": "foreign-property"}'
  ```

- In LibreMTD's HMRC settings, click the "Foreign property business ID" "Fetch from HMRC".

### Automatic logon

Environment variables `DEV_PASSWORD` and `DEV_USERNAME` can be used to automate logon.

### Files

- Log — `~/.local/state/LibreMTD/log/app.log`
- Database — `~/.local/share/LibreMTD/app.db`

### UI text copy

Most of the UI text can be copied to the clipboard by context-clicking and choosing Copy.

### Warnings

**"WARNING: Unsupported JavaFX configuration: classes were loaded from 'unnamed module @4b553d26'"**

Can be ignored.