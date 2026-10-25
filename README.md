# Principal's Office

A notice board for students of Deeksha Vedantu. Notices are managed in a Google Sheet and shown as Apple-style cards. A notice can be a plain update, an FAQ-style dropdown, a link that redirects, or a form (with text, dropdowns, pictures and PDFs). No build step and no dependencies.

## Files

| File | What it is |
|---|---|
| `index.html` | The whole student-facing page (HTML, CSS and JS in one file). |
| `Code.gs` | Google Apps Script backend. Reads the sheet for the page and saves form submissions. |
| `android/` | The Android app (APK). See `android/README.md`. |
| `.github/workflows/build-apk.yml` | Builds the APK on GitHub. |
| `README.md` | This file. |

## How it works

```
Google Sheet  <-->  Apps Script web app (Code.gs)  <-->  index.html (GitHub Pages or any static host)
```

The page loads everything from one Apps Script URL, so edits in the sheet appear on the next page load. There is no need to redeploy `index.html`.

## 1. Set up the Google Sheet

Create one spreadsheet with these sheets.

### `Settings` (page header)

Column A is the setting, column B is the value.

| A | B |
|---|---|
| School Name | Deeksha Vedantu |
| Heading | Principal's Office |
| Subheading | Notices, updates and forms for students. |

The browser tab title is built from School Name and Heading. If the sheet or a row is missing, School Name and Heading fall back to "Deeksha Vedantu" and "Principal's Office". A blank Subheading hides that line.

### `Notices` (one row per notice, row 1 = headers)

Headers can be in any order and are case-insensitive.

| Header | Purpose |
|---|---|
| Title | Required. |
| Summary | Short line under the title. |
| Type | `Info`, `Dropdown`, `Link` or `Form`. If blank: Form is set means Form, URL is set means Link, Details is set means Dropdown, otherwise Info. |
| Details | Text revealed when a Dropdown card is opened (URLs become links). Shown directly on other types. |
| URL | Redirect address for `Link` notices. |
| Button Text | Label for the Link or Form button (optional). |
| Form | For `Form` notices: the name of the form sheet. A partial name works, e.g. `Leave` matches `Leave Request - Active`. |
| Image | Picture URL or Google Drive share link (the file must be viewable by link). |
| PDF | PDF URL or Google Drive share link. Adds a "View PDF" button. |
| Date | Shown as typed. |
| Tag | Small coloured pill on the card (optional). |
| Color | Optional. Cards are coloured by position: red, orange, purple, blue, then the same four again for the fifth card onwards. Fill this in only to override one notice: `blue`, `green`, `orange`, `pink`, `purple`, `teal`, `indigo`, `red`, `yellow` or `gray`, or a hex code such as `#ff8a00`. |
| Pinned | `Yes` moves the notice to the top. Internal only: nothing about it is shown on the card. |
| Show | `No` or `Hidden` hides the notice. |

### Form sheets (any sheet whose name contains `Active`)

Example name: `Leave Request - Active`.

- Row 1: field labels.
- Row 2: field types.
- Rows 3 and below: options for Dropdown, Radio and Checkbox columns.
- Add `*` to a label (or type) to make the field required.

| | A | B | C | D | E |
|---|---|---|---|---|---|
| Row 1 | Name* | Class | Reason | Document | Photo |
| Row 2 | Text | Dropdown | Long text | PDF | Picture |
| Row 3 | | Class 8 | | | |
| Row 4 | | Class 9 | | | |
| Row 5 | | Class 10 | | | |

Supported types: Text, Long text, Number, Email, Phone, Date, Time, URL, Dropdown, Radio, Checkbox, Picture, PDF, File. Options can also be written inline, e.g. `Dropdown: A, B, C`.

To switch a form off, rename its sheet so it no longer contains "Active".

Submissions are saved in the sheet named by the text **before "Active"** in the form sheet's name, ignoring the separator: `Aadhar collection - Active` saves to `Aadhar collection`. If that sheet doesn't exist, it is created on the first submission. Each row gets a Timestamp and the Notice title, followed by one column per field, and new columns are added automatically when you add fields to the form. If the name has nothing before "Active" (e.g. `Active - Leave`), the text after it is used. A form called `Notices - Active` or `Settings - Active` would clash with those sheets, so it saves to `Notices Responses` or `Settings Responses`.

Uploaded pictures and PDFs are saved in your Drive (private, only you can open them) using this layout, and the response row holds the file link:

```
Principal Office Uploads / Aadhar collection / Rahul Sharma / Rahul Sharma.pdf
        (upload folder)      (form name)        (first field)     (file named after it)
```

- The upload folder `Principal Office Uploads` is reused if it exists and created once if not. Folder ids are remembered by the script, so the same folders are used every time. To pin an exact folder, paste its id into `UPLOAD_FOLDER_ID` at the top of `Code.gs`.
- The form folder uses the same name as the response sheet.
- Each entry gets its own folder named after the first answer in the form (the first field that isn't a file or checkbox, normally Name). If that name is already taken, it becomes `Rahul Sharma (2)`, and so on. If the first answer is blank, the folder is named `Entry <date time>`.
- A single file is renamed to the name (`Rahul Sharma.pdf`). If a form has several file fields, each file is named `Rahul Sharma - <field label>.<ext>`.
- Forms with no uploaded file create no folders.
- Folders from before this layout are left alone. If you already have several folders called `Principal Office Uploads`, delete or merge the extras, or set `UPLOAD_FOLDER_ID`, so the script picks the right one.

## 2. Deploy the backend

1. In the spreadsheet, open **Extensions > Apps Script**.
2. Replace the editor content with the contents of `Code.gs` and save.
3. **Deploy > New deployment**, type **Web app**.
   - Execute as: **Me**
   - Who has access: **Anyone**
4. Authorise when asked, then copy the **Web app URL**.
5. In `index.html`, paste it into `CONFIG.SCRIPT_URL`:

   ```js
   const CONFIG = {
     SCRIPT_URL: 'https://script.google.com/macros/s/XXXXXXXX/exec',
     MAX_FILE_MB: 5,
     CACHE_KEY: 'po-notices-v1'
   };
   ```

When you change `Code.gs` later, use **Deploy > Manage deployments > Edit > New version**. The URL stays the same.

While `SCRIPT_URL` is empty, the page runs in preview mode with sample notices and a sample form (submissions are simulated), which is handy for checking the design.

## 3. Publish the page

Host `index.html` on any static host. For GitHub Pages: push the repo, then **Settings > Pages > Deploy from branch > `main` / root**.

## 4. Android app

The `android/` folder builds an APK that opens this page full-screen, with the Principal's Office logo and splash screen. GitHub builds it for you: see `android/README.md`.

## Notes

- **Public data.** Anyone with the page link can read the `Settings` and `Notices` sheets and the form sheets through the web app. Keep private information out of them. Response sheets and uploaded files are not exposed.
- **Uploads.** Each file is limited to 5 MB. Images are resized in the browser before upload.
- **Spam.** Forms include a hidden honeypot field. Submissions are validated again on the server against the form sheet (required fields, dropdown options, file types), and values that start with `=`, `+`, `-` or `@` are neutralised so they cannot run as spreadsheet formulas.
- **Caching.** The page keeps the last loaded copy in the browser and refreshes it on every visit.
- **Troubleshooting.** If the page shows "Could not load notices", open the Web app URL directly in a browser. You should see JSON. If you see a Google sign-in page, redeploy with access set to **Anyone**.
