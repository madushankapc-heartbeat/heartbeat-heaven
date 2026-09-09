const uploadForm = document.getElementById("uploadForm");
const editForm = document.getElementById("editForm");

const status = document.getElementById("status");
const editStatus = document.getElementById("editStatus");

const list = document.getElementById("adminSongs");

const editPanel = document.getElementById("editPanel");
const cancelEdit = document.getElementById("cancelEdit");

const versionSettings =
  document.getElementById("versionSettings");

const parentSongSelect =
  document.getElementById("parentSongSelect");

const versionName =
  document.getElementById("versionName");

const uploadCover =
  document.getElementById("uploadCover");

const coverHint =
  document.getElementById("coverHint");

const editVersionInfo =
  document.getElementById("editVersionInfo");

const editParentTitle =
  document.getElementById("editParentTitle");

const editVersionName =
  document.getElementById("editVersionName");

let songs = [];
let allStudioSongs = [];


/* =========================================================
   PROGRESS UI
========================================================= */

function createProgressUI(container) {

  let box = container.querySelector(".hh-progress");

  if (!box) {

    box = document.createElement("div");

    box.className = "hh-progress";

    box.innerHTML = `
      <div class="hh-progress-top">

        <span class="hh-progress-stage">
          Preparing…
        </span>

        <strong class="hh-progress-percent">
          0%
        </strong>

      </div>

      <div class="hh-progress-track">

        <div class="hh-progress-bar"></div>

      </div>

      <div class="hh-progress-message">
        Please wait…
      </div>
    `;

    container.appendChild(box);
  }

  return box;
}


function updateProgress(
  container,
  percent,
  stage,
  message
) {

  const box =
    container.querySelector(".hh-progress");

  if (!box) return;

  const bar =
    box.querySelector(".hh-progress-bar");

  const percentText =
    box.querySelector(".hh-progress-percent");

  const stageText =
    box.querySelector(".hh-progress-stage");

  const messageText =
    box.querySelector(".hh-progress-message");


  const safePercent =
    Math.max(
      0,
      Math.min(
        100,
        Math.round(percent)
      )
    );


  bar.style.width =
    safePercent + "%";


  percentText.textContent =
    safePercent + "%";


  stageText.textContent =
    stage || "";


  messageText.textContent =
    message || "";
}


function finishProgress(
  container,
  success = true,
  message = ""
) {

  const box =
    container.querySelector(".hh-progress");

  if (!box) return;

  const bar =
    box.querySelector(".hh-progress-bar");

  const percentText =
    box.querySelector(".hh-progress-percent");

  const stageText =
    box.querySelector(".hh-progress-stage");

  const messageText =
    box.querySelector(".hh-progress-message");


  if (success) {

    bar.style.width =
      "100%";

    percentText.textContent =
      "100%";

    stageText.textContent =
      "Published successfully";

    messageText.textContent =
      message ||
      "Your song is now live.";

    box.classList.add("success");

  } else {

    stageText.textContent =
      "Upload failed";

    messageText.textContent =
      message ||
      "Please try again.";

    box.classList.add("error");

  }

}


/* =========================================================
   PROGRESS CSS
========================================================= */

(function addProgressStyles() {

  if (
    document.getElementById(
      "hh-progress-styles"
    )
  ) {
    return;
  }

  const style =
    document.createElement("style");

  style.id =
    "hh-progress-styles";

  style.textContent = `

    .hh-progress {
      margin-top: 16px;
      padding: 15px 16px;
      border: 1px solid rgba(255,255,255,.10);
      border-radius: 14px;
      background: rgba(255,255,255,.035);
      transition: opacity .25s ease;
    }

    .hh-progress-top {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      margin-bottom: 10px;
    }

    .hh-progress-stage {
      font-size: 13px;
      font-weight: 600;
    }

    .hh-progress-percent {
      font-size: 14px;
      white-space: nowrap;
    }

    .hh-progress-track {
      width: 100%;
      height: 8px;
      overflow: hidden;
      border-radius: 999px;
      background: rgba(255,255,255,.10);
    }

    .hh-progress-bar {
      width: 0%;
      height: 100%;
      border-radius: inherit;
      background: currentColor;
      transition: width .18s ease;
    }

    .hh-progress-message {
      margin-top: 9px;
      font-size: 12px;
      opacity: .68;
      line-height: 1.45;
    }

    .hh-progress.success {
      border-color: rgba(80,220,130,.28);
    }

    .hh-progress.error {
      border-color: rgba(255,80,80,.28);
    }

    .hh-progress.error .hh-progress-bar {
      width: 100%;
    }

    .hh-uploading {
      opacity: .72;
      pointer-events: none;
    }

    .content-type-box {
      margin-bottom: 20px;
      padding: 15px;
      border: 1px solid rgba(255,255,255,.10);
      border-radius: 14px;
      background: rgba(255,255,255,.025);
    }

    .field-title {
      margin: 0 0 12px;
      font-size: 13px;
      font-weight: 700;
    }

    .content-type-options {
      display: grid;
      gap: 10px;
    }

    .content-type-option {
      display: flex;
      align-items: flex-start;
      gap: 10px;
      padding: 12px;
      border-radius: 12px;
      border: 1px solid rgba(255,255,255,.08);
      cursor: pointer;
    }

    .content-type-option input {
      margin-top: 3px;
      flex: 0 0 auto;
    }

    .content-type-option span {
      display: grid;
      gap: 3px;
    }

    .content-type-option small {
      opacity: .62;
      line-height: 1.4;
    }

    .version-settings,
    .version-info {
      margin-bottom: 20px;
      padding: 15px;
      border: 1px solid rgba(255,255,255,.10);
      border-radius: 14px;
      background: rgba(255,255,255,.025);
    }

    .version-info > p {
      margin-top: 0;
    }

    .version-badge {
      display: inline-flex;
      align-items: center;
      padding: 4px 8px;
      border-radius: 999px;
      background: rgba(255,255,255,.08);
      font-size: 11px;
      margin-top: 6px;
    }

    .version-list {
      margin: 10px 0 0 56px;
      display: grid;
      gap: 7px;
    }

    .version-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 10px;
      padding: 9px 11px;
      border-radius: 10px;
      background: rgba(255,255,255,.035);
      border: 1px solid rgba(255,255,255,.06);
    }

    .version-row-info {
      min-width: 0;
    }

    .version-row-info strong {
      display: block;
      font-size: 13px;
    }

    .version-row-info small {
      display: block;
      opacity: .58;
      margin-top: 2px;
    }

    .version-actions {
      display: flex;
      gap: 6px;
      flex-shrink: 0;
    }

    .admin-original {
      border-bottom: 1px solid rgba(255,255,255,.08);
      padding-bottom: 10px;
    }

    .admin-original-label {
      font-size: 10px;
      letter-spacing: .08em;
      opacity: .55;
      margin-top: 5px;
      text-transform: uppercase;
    }

    @media (max-width: 600px) {

      .hh-progress {
        padding: 13px;
      }

      .hh-progress-stage {
        font-size: 12px;
      }

      .hh-progress-percent {
        font-size: 13px;
      }

      .version-list {
        margin-left: 0;
      }

      .version-row {
        align-items: flex-start;
      }

      .version-actions {
        flex-direction: column;
      }

    }

  `;

  document.head.appendChild(style);

})();


/* =========================================================
   CONTENT TYPE
========================================================= */

function getContentType() {

  const selected =
    uploadForm.querySelector(
      'input[name="content_type"]:checked'
    );

  return selected?.value || "original";
}


function updateContentTypeUI() {

  const type =
    getContentType();


  const isVersion =
    type === "version";


  if (versionSettings) {

    versionSettings.classList.toggle(
      "hidden",
      !isVersion
    );

  }


  if (parentSongSelect) {

    parentSongSelect.required =
      isVersion;

  }


  if (versionName) {

    versionName.required =
      isVersion;

  }


  if (uploadCover) {

    uploadCover.required =
      !isVersion;

  }


  if (coverHint) {

    coverHint.textContent =
      isVersion
        ? "Optional. Leave empty to keep the original song's cover."
        : "Required for an original song.";

  }

}


document
  .querySelectorAll(
    'input[name="content_type"]'
  )
  .forEach(
    radio => {

      radio.addEventListener(
        "change",
        updateContentTypeUI
      );

    }
  );


/* =========================================================
   GET SIGNED SUPABASE UPLOAD URL
========================================================= */

async function getUploadUrl(
  file,
  bucket
) {

  const response =
    await fetch(
      "/api/studio/upload-url",
      {

        method: "POST",

        headers: {

          "Content-Type":
            "application/json",

          "Accept":
            "application/json"

        },

        credentials:
          "same-origin",

        body:
          JSON.stringify({

            bucket,

            name:
              file.name,

            contentType:
              file.type ||
              "application/octet-stream"

          })

      }
    );


  let data = {};

  try {

    data =
      await response.json();

  } catch {

    data = {};

  }


  if (!response.ok) {

    throw new Error(
      data.error ||
      data.message ||
      "Unable to prepare secure upload."
    );

  }


  if (
    !data.signed_url ||
    !data.path
  ) {

    console.error(
      "Incomplete upload configuration:",
      data
    );

    throw new Error(
      "Server returned an incomplete upload configuration."
    );

  }


  return data;

}


/* =========================================================
   DIRECT SUPABASE SIGNED URL UPLOAD
========================================================= */

function uploadToSupabase(
  file,
  uploadInfo,
  onProgress
) {

  return new Promise(
    (resolve, reject) => {

      const xhr =
        new XMLHttpRequest();


      xhr.open(
        "PUT",
        uploadInfo.signed_url,
        true
      );


      xhr.setRequestHeader(
        "Content-Type",
        uploadInfo.content_type ||
        file.type ||
        "application/octet-stream"
      );


      xhr.setRequestHeader(
        "Accept",
        "application/json"
      );


      xhr.upload.addEventListener(
        "progress",
        function(event) {

          if (!event.lengthComputable) {
            return;
          }

          const percent =
            (
              event.loaded /
              event.total
            ) * 100;


          onProgress(
            percent,
            event.loaded,
            event.total
          );

        }
      );


      xhr.onload =
        function() {

          let responseData = {};

          try {

            responseData =
              xhr.responseText
                ? JSON.parse(
                    xhr.responseText
                  )
                : {};

          } catch {

            responseData = {};

          }


          if (
            xhr.status >= 200 &&
            xhr.status < 300
          ) {

            resolve(
              uploadInfo
            );

            return;

          }


          console.error(
            "Supabase upload failed:",
            xhr.status,
            xhr.responseText
          );


          reject(
            new Error(
              responseData.message ||
              responseData.error ||
              `Storage upload failed (${xhr.status}).`
            )
          );

        };


      xhr.onerror =
        function() {

          reject(
            new Error(
              "Network error while uploading the file to Supabase."
            )
          );

        };


      xhr.onabort =
        function() {

          reject(
            new Error(
              "Upload was cancelled."
            )
          );

        };


      xhr.ontimeout =
        function() {

          reject(
            new Error(
              "Upload timed out. Please try again."
            )
          );

        };


      xhr.send(file);

    }
  );

}


/* =========================================================
   UPLOAD ONE FILE
========================================================= */

async function uploadFile(
  file,
  bucket,
  container,
  basePercent,
  rangePercent,
  label
) {

  if (!file) {
    return null;
  }


  updateProgress(
    container,
    basePercent,
    `Preparing ${label}`,
    `Creating secure upload for ${label}…`
  );


  const uploadInfo =
    await getUploadUrl(
      file,
      bucket
    );


  updateProgress(
    container,
    basePercent,
    `Uploading ${label}`,
    `Uploading ${label}… 0%`
  );


  await uploadToSupabase(
    file,
    uploadInfo,
    function(percent) {

      const overall =
        basePercent +
        (
          percent /
          100
        ) *
        rangePercent;


      updateProgress(
        container,
        overall,
        `Uploading ${label}`,
        `Uploading ${label}… ${Math.round(percent)}%`
      );

    }
  );


  updateProgress(
    container,
    basePercent +
      rangePercent,
    `${label} uploaded`,
    `${label} uploaded successfully.`
  );


  return uploadInfo.path;

}


/* =========================================================
   GET FORM VALUE
========================================================= */

function getFormValue(
  form,
  name,
  fallback = ""
) {

  if (!form) {
    return fallback;
  }


  const data =
    new FormData(form);


  const value =
    data.get(name);


  if (
    value === null ||
    value === undefined
  ) {

    return fallback;

  }


  return String(value).trim();

}


/* =========================================================
   LOAD STUDIO SONGS
========================================================= */

async function loadStudioSongs() {

  try {

    const response =
      await fetch(
        "/api/studio/songs",
        {
          credentials:
            "same-origin"
        }
      );


    if (!response.ok) {

      throw new Error(
        "Unable to load studio songs."
      );

    }


    const data =
      await response.json();


    allStudioSongs =
      Array.isArray(data)
        ? data
        : [];


    songs =
      allStudioSongs.filter(
        song =>
          !song.parent_song_id
      );


    populateParentSongs();

    renderSongs();

  } catch (error) {

    console.error(
      "Studio song loading error:",
      error
    );


    list.innerHTML =
      '<div class="empty">Unable to load songs.</div>';

  }

}


/* =========================================================
   POPULATE ORIGINAL SONG DROPDOWN
========================================================= */

function populateParentSongs() {

  if (!parentSongSelect) {
    return;
  }


  const originals =
    allStudioSongs.filter(
      song =>
        !song.parent_song_id
    );


  parentSongSelect.innerHTML = `

    <option value="">
      Select original song
    </option>

    ${
      originals.map(
        song => `

          <option value="${esc(song.id)}">

            ${esc(song.title)}
            —
            ${esc(song.artist || "Madushanka")}

          </option>

        `
      ).join("")
    }

  `;

}


/* =========================================================
   RENDER SONG LIST
========================================================= */

function renderSongs() {

  if (!songs.length) {

    list.innerHTML =
      '<div class="empty">No songs yet.</div>';

    return;
  }


  list.innerHTML =
    songs.map(
      song => {

        const versions =
          allStudioSongs.filter(
            item =>
              Number(item.parent_song_id) ===
              Number(song.id)
          );


        return `

          <div class="admin-original">

            <div class="admin-row">

              <div class="admin-song-info">

                <img
                  class="admin-thumb"
                  src="${esc(song.cover_url || "")}"
                  alt=""
                >

                <div>

                  <b>
                    ${esc(song.title)}
                  </b>

                  <div class="admin-original-label">
                    Original Song
                  </div>

                  <small class="meta">

                    ${esc(
                      song.artist ||
                      "Madushanka"
                    )}

                    •

                    ${esc(
                      song.genre ||
                      ""
                    )}

                    •

                    ${esc(
                      song.language ||
                      ""
                    )}

                  </small>

                </div>

              </div>


              <div class="admin-actions">

                <button
                  class="btn ghost edit-btn"
                  onclick="editSong(${song.id})"
                >
                  Edit
                </button>


                <button
                  class="delete"
                  onclick="deleteSong(${song.id})"
                >
                  Delete
                </button>

              </div>

            </div>


            ${
              versions.length
                ? `

                  <div class="version-list">

                    ${
                      versions.map(
                        version => `

                          <div class="version-row">

                            <div class="version-row-info">

                              <strong>
                                ${esc(
                                  version.version_name ||
                                  "Version"
                                )}
                              </strong>

                              <small>

                                ${esc(
                                  version.title ||
                                  song.title
                                )}

                                •

                                ${esc(
                                  version.artist ||
                                  song.artist ||
                                  "Madushanka"
                                )}

                              </small>

                            </div>


                            <div class="version-actions">

                              <button
                                class="btn ghost"
                                onclick="editSong(${version.id})"
                              >
                                Edit
                              </button>

                              <button
                                class="delete"
                                onclick="deleteSong(${version.id})"
                              >
                                Delete
                              </button>

                            </div>

                          </div>

                        `
                      ).join("")
                    }

                  </div>

                `
                : ""
            }

          </div>

        `;

      }
    ).join("");

}


/* =========================================================
   ORIGINAL SONG SELECTION
========================================================= */

if (parentSongSelect) {

  parentSongSelect.addEventListener(
    "change",
    function() {

      const parentId =
        this.value;


      if (!parentId) {
        return;
      }


      const parent =
        songs.find(
          song =>
            String(song.id) ===
            String(parentId)
        );


      if (!parent) {
        return;
      }


      /*
        A version normally belongs to the
        same song, so make the title equal
        to the original title.

        User can still change it afterwards.
      */

      const titleInput =
        uploadForm.querySelector(
          'input[name="title"]'
        );


      if (
        titleInput &&
        !titleInput.value.trim()
      ) {

        titleInput.value =
          parent.title || "";

      }


      const artistInput =
        uploadForm.querySelector(
          'input[name="artist"]'
        );


      if (
        artistInput &&
        (
          !artistInput.value.trim() ||
          artistInput.value === "Madushanka"
        )
      ) {

        artistInput.value =
          parent.artist ||
          "Madushanka";

      }


      const languageInput =
        uploadForm.querySelector(
          'select[name="language"]'
        );


      if (languageInput && parent.language) {

        languageInput.value =
          parent.language;

      }


      const genreInput =
        uploadForm.querySelector(
          'select[name="genre"]'
        );


      if (genreInput && parent.genre) {

        genreInput.value =
          parent.genre;

      }


      const moodInput =
        uploadForm.querySelector(
          'input[name="mood"]'
        );


      if (
        moodInput &&
        parent.mood
      ) {

        moodInput.value =
          parent.mood;

      }


      const descriptionInput =
        uploadForm.querySelector(
          'textarea[name="description"]'
        );


      if (
        descriptionInput &&
        !descriptionInput.value.trim()
      ) {

        descriptionInput.value =
          parent.description || "";

      }


      const lyricsInput =
        uploadForm.querySelector(
          'textarea[name="lyrics"]'
        );


      if (
        lyricsInput &&
        !lyricsInput.value.trim()
      ) {

        lyricsInput.value =
          parent.lyrics || "";

      }

    }
  );

}


/* =========================================================
   UPLOAD NEW SONG / VERSION
========================================================= */

uploadForm.onsubmit =
  async function(e) {

    e.preventDefault();


    status.className = "";

    status.textContent = "";


    const contentType =
      getContentType();


    const isVersion =
      contentType === "version";


    const submitButton =
      uploadForm.querySelector(
        'button[type="submit"]'
      );


    const audio =
      uploadForm.querySelector(
        'input[name="audio"]'
      )?.files?.[0];


    const cover =
      uploadForm.querySelector(
        'input[name="cover"]'
      )?.files?.[0];


    if (!audio) {

      status.className =
        "error";

      status.textContent =
        "Please select an audio file.";

      return;

    }


    if (
      !isVersion &&
      !cover
    ) {

      status.className =
        "error";

      status.textContent =
        "Please select a cover image for the original song.";

      return;

    }


    const parentId =
      getFormValue(
        uploadForm,
        "parent_song_id"
      );


    const selectedVersionName =
      getFormValue(
        uploadForm,
        "version_name"
      );


    if (
      isVersion &&
      !parentId
    ) {

      status.className =
        "error";

      status.textContent =
        "Please select the original song.";

      return;

    }


    if (
      isVersion &&
      !selectedVersionName
    ) {

      status.className =
        "error";

      status.textContent =
        "Please enter a version name.";

      return;

    }


    const progress =
      createProgressUI(
        uploadForm
      );


    uploadForm.classList.add(
      "hh-uploading"
    );


    submitButton.disabled =
      true;


    submitButton.dataset.originalText =
      submitButton.textContent;


    submitButton.textContent =
      isVersion
        ? "Uploading Version…"
        : "Uploading…";


    updateProgress(
      uploadForm,
      0,
      "Preparing upload",
      "Preparing your song files…"
    );


    try {

      /* =====================================================
         COVER
         Original:
         0% → 15%

         Version:
         0% → 15% only if cover exists
      ===================================================== */

      let coverPath = null;


      if (cover) {

        coverPath =
          await uploadFile(
            cover,
            "covers",
            uploadForm,
            0,
            15,
            "Cover"
          );

      } else {

        updateProgress(
          uploadForm,
          0,
          "Cover skipped",
          "Using the original song cover for this version."
        );

      }


      /* =====================================================
         AUDIO
      ===================================================== */

      const audioStart =
        cover
          ? 15
          : 0;


      const audioRange =
        95 -
        audioStart;


      const audioPath =
        await uploadFile(
          audio,
          "audio",
          uploadForm,
          audioStart,
          audioRange,
          "Audio"
        );


      /* =====================================================
         FINAL DATABASE PUBLICATION
      ===================================================== */

      updateProgress(
        uploadForm,
        95,
        "Publishing",
        isVersion
          ? "Adding this version to the Song Hub…"
          : "Saving song details and finishing publication…"
      );


      const metadata = {

        title:
          getFormValue(
            uploadForm,
            "title"
          ),

        artist:
          getFormValue(
            uploadForm,
            "artist",
            "Madushanka"
          ) ||
          "Madushanka",

        language:
          getFormValue(
            uploadForm,
            "language"
          ),

        genre:
          getFormValue(
            uploadForm,
            "genre"
          ),

        mood:
          getFormValue(
            uploadForm,
            "mood"
          ),

        description:
          getFormValue(
            uploadForm,
            "description"
          ),

        lyrics:
          getFormValue(
            uploadForm,
            "lyrics"
          ),

        release_date:
          getFormValue(
            uploadForm,
            "release_date"
          ),

        cover_path:
          coverPath,

        audio_path:
          audioPath

      };


      if (isVersion) {

        metadata.parent_song_id =
          Number(parentId);

        metadata.version_name =
          selectedVersionName;

      } else {

        /*
          Explicitly tell server this is
          a new original song.
        */

        metadata.parent_song_id =
          null;

        metadata.version_name =
          "Original Version";

      }


      console.log(
        "Publishing metadata:",
        metadata
      );


      const response =
        await fetch(
          "/api/songs",
          {

            method: "POST",

            headers: {

              "Content-Type":
                "application/json",

              "Accept":
                "application/json"

            },

            credentials:
              "same-origin",

            body:
              JSON.stringify(
                metadata
              )

          }
        );


      let result = {};

      try {

        result =
          await response.json();

      } catch {

        result = {};

      }


      if (!response.ok) {

        throw new Error(
          result.error ||
          result.message ||
          "Song publication failed."
        );

      }


      /* SUCCESS */

      finishProgress(
        uploadForm,
        true,
        isVersion
          ? "Version published and added to the Song Hub."
          : "Your song has been published successfully."
      );


      status.className =
        "success";

      status.textContent =
        isVersion
          ? "Version published successfully."
          : "Published successfully.";


      uploadForm.reset();


      if (
        uploadForm.artist
      ) {

        uploadForm.artist.value =
          "Madushanka";

      }


      updateContentTypeUI();


      await loadStudioSongs();


    } catch (error) {

      console.error(
        "Song upload error:",
        error
      );


      finishProgress(
        uploadForm,
        false,
        error.message ||
        "Something went wrong."
      );


      status.className =
        "error";

      status.textContent =
        error.message ||
        "Something went wrong. Please try again.";

    } finally {

      uploadForm.classList.remove(
        "hh-uploading"
      );


      submitButton.disabled =
        false;


      submitButton.textContent =
        submitButton.dataset.originalText ||
        "Publish Song";

    }

  };


/* =========================================================
   OPEN EDIT FORM
========================================================= */

function editSong(id) {

  const song =
    allStudioSongs.find(
      s =>
        Number(s.id) ===
        Number(id)
    );


  if (!song) {
    return;
  }


  document.getElementById(
    "editId"
  ).value =
    song.id;


  document.getElementById(
    "editTitle"
  ).value =
    song.title || "";


  document.getElementById(
    "editArtist"
  ).value =
    song.artist ||
    "Madushanka";


  document.getElementById(
    "editLanguage"
  ).value =
    song.language ||
    "Sinhala";


  document.getElementById(
    "editGenre"
  ).value =
    song.genre ||
    "Romantic";


  document.getElementById(
    "editMood"
  ).value =
    song.mood || "";


  document.getElementById(
    "editDescription"
  ).value =
    song.description || "";


  document.getElementById(
    "editLyrics"
  ).value =
    song.lyrics || "";


  document.getElementById(
    "editReleaseDate"
  ).value =
    song.release_date || "";


  const preview =
    document.getElementById(
      "editCoverPreview"
    );


  if (preview) {

    preview.src =
      song.cover_url || "";

  }


  const isVersion =
    !!song.parent_song_id;


  if (editVersionInfo) {

    editVersionInfo.classList.toggle(
      "hidden",
      !isVersion
    );

  }


  if (isVersion) {

    const parent =
      allStudioSongs.find(
        item =>
          Number(item.id) ===
          Number(song.parent_song_id)
      );


    if (editParentTitle) {

      editParentTitle.textContent =
        parent?.title ||
        "Original Song";

    }


    if (editVersionName) {

      editVersionName.value =
        song.version_name ||
        "Version";

    }

  } else {

    if (editParentTitle) {

      editParentTitle.textContent =
        "";

    }


    if (editVersionName) {

      editVersionName.value =
        "";

    }

  }


  editStatus.className = "";

  editStatus.textContent = "";


  const oldProgress =
    editForm.querySelector(
      ".hh-progress"
    );


  if (oldProgress) {

    oldProgress.remove();

  }


  editPanel.classList.remove(
    "hidden"
  );


  editPanel.scrollIntoView({
    behavior: "smooth",
    block: "start"
  });

}


/* =========================================================
   CANCEL EDIT
========================================================= */

cancelEdit.onclick =
  function() {

    editPanel.classList.add(
      "hidden"
    );


    editForm.reset();


    editStatus.className = "";

    editStatus.textContent = "";


    const progress =
      editForm.querySelector(
        ".hh-progress"
      );


    if (progress) {

      progress.remove();

    }

  };


/* =========================================================
   SAVE EDIT
========================================================= */

editForm.onsubmit =
  async function(e) {

    e.preventDefault();


    const id =
      document.getElementById(
        "editId"
      ).value;


    if (!id) {

      editStatus.className =
        "error";

      editStatus.textContent =
        "Song ID is missing.";

      return;

    }


    const song =
      allStudioSongs.find(
        item =>
          Number(item.id) ===
          Number(id)
      );


    if (!song) {

      editStatus.className =
        "error";

      editStatus.textContent =
        "Song could not be found.";

      return;

    }


    editStatus.className = "";

    editStatus.textContent = "";


    const submitButton =
      editForm.querySelector(
        'button[type="submit"]'
      );


    const audio =
      editForm.querySelector(
        'input[name="audio"]'
      )?.files?.[0];


    const cover =
      editForm.querySelector(
        'input[name="cover"]'
      )?.files?.[0];


    const hasAudio =
      !!audio;


    const hasCover =
      !!cover;


    const isVersion =
      !!song.parent_song_id;


    createProgressUI(
      editForm
    );


    editForm.classList.add(
      "hh-uploading"
    );


    submitButton.disabled =
      true;


    submitButton.dataset.originalText =
      submitButton.textContent;


    submitButton.textContent =
      "Saving…";


    updateProgress(
      editForm,
      0,
      "Preparing changes",
      "Preparing your changes…"
    );


    try {

      let coverPath = null;


      /* =====================================================
         REPLACEMENT COVER
      ===================================================== */

      if (hasCover) {

        coverPath =
          await uploadFile(
            cover,
            "covers",
            editForm,
            0,
            20,
            "Cover"
          );

      }


      /* =====================================================
         REPLACEMENT AUDIO
      ===================================================== */

      let audioPath = null;


      if (hasAudio) {

        const audioStart =
          hasCover
            ? 20
            : 0;


        const audioRange =
          95 -
          audioStart;


        audioPath =
          await uploadFile(
            audio,
            "audio",
            editForm,
            audioStart,
            audioRange,
            "Audio"
          );

      }


      /* =====================================================
         NO FILE CHANGES
      ===================================================== */

      if (
        !hasAudio &&
        !hasCover
      ) {

        updateProgress(
          editForm,
          70,
          "Saving changes",
          "Updating song information…"
        );

      }


      /* =====================================================
         FINAL DATABASE UPDATE
      ===================================================== */

      updateProgress(
        editForm,
        95,
        "Finalizing",
        "Saving changes and updating the song…"
      );


      const metadata = {

        title:
          getFormValue(
            editForm,
            "title"
          ),

        artist:
          getFormValue(
            editForm,
            "artist",
            "Madushanka"
          ) ||
          "Madushanka",

        language:
          getFormValue(
            editForm,
            "language"
          ),

        genre:
          getFormValue(
            editForm,
            "genre"
          ),

        mood:
          getFormValue(
            editForm,
            "mood"
          ),

        description:
          getFormValue(
            editForm,
            "description"
          ),

        lyrics:
          getFormValue(
            editForm,
            "lyrics"
          ),

        release_date:
          getFormValue(
            editForm,
            "release_date"
          )

      };


      if (coverPath) {

        metadata.cover_path =
          coverPath;

      }


      if (audioPath) {

        metadata.audio_path =
          audioPath;

      }


      /*
        Only versions can have their
        version name edited.

        Parent/original relationship is
        intentionally NOT changed here.
      */

      if (isVersion) {

        const newVersionName =
          getFormValue(
            editForm,
            "version_name"
          );


        if (!newVersionName) {

          throw new Error(
            "Version name is required."
          );

        }


        metadata.version_name =
          newVersionName;

      }


      console.log(
        "Updating metadata:",
        metadata
      );


      const response =
        await fetch(
          "/api/songs/" + id,
          {

            method: "PUT",

            headers: {

              "Content-Type":
                "application/json",

              "Accept":
                "application/json"

            },

            credentials:
              "same-origin",

            body:
              JSON.stringify(
                metadata
              )

          }
        );


      let result = {};

      try {

        result =
          await response.json();

      } catch {

        result = {};

      }


      if (!response.ok) {

        throw new Error(
          result.error ||
          result.message ||
          "Update failed."
        );

      }


      /* SUCCESS */

      finishProgress(
        editForm,
        true,
        isVersion
          ? "Version changes saved successfully."
          : "Your changes have been saved successfully."
      );


      editStatus.className =
        "success";

      editStatus.textContent =
        "Changes saved successfully.";


      await loadStudioSongs();


      setTimeout(
        function() {

          editPanel.classList.add(
            "hidden"
          );


          editForm.reset();


          editStatus.textContent =
            "";


          const progress =
            editForm.querySelector(
              ".hh-progress"
            );


          if (progress) {

            progress.remove();

          }

        },
        900
      );


    } catch (error) {

      console.error(
        "Edit error:",
        error
      );


      finishProgress(
        editForm,
        false,
        error.message ||
        "Something went wrong."
      );


      editStatus.className =
        "error";

      editStatus.textContent =
        error.message ||
        "Something went wrong. Please try again.";

    } finally {

      editForm.classList.remove(
        "hh-uploading"
      );


      submitButton.disabled =
        false;


      submitButton.textContent =
        submitButton.dataset.originalText ||
        "Save Changes";

    }

  };


/* =========================================================
   DELETE SONG / VERSION
========================================================= */

async function deleteSong(id) {

  const song =
    allStudioSongs.find(
      s =>
        Number(s.id) ===
        Number(id)
    );


  const title =
    song?.title ||
    "this song";


  const isVersion =
    !!song?.parent_song_id;


  const label =
    isVersion
      ? (
          song.version_name ||
          "this version"
        )
      : title;


  if (
    !confirm(
      isVersion
        ? `Delete "${label}" from the Song Hub and its files?`
        : `Delete "${title}" and its files?`
    )
  ) {

    return;

  }


  try {

    const response =
      await fetch(
        "/api/songs/" + id,
        {

          method: "DELETE",

          credentials:
            "same-origin"

        }
      );


    let data = {};

    try {

      data =
        await response.json();

    } catch {

      data = {};

    }


    if (!response.ok) {

      alert(
        data.error ||
        data.message ||
        "Delete failed."
      );

      return;

    }


    await loadStudioSongs();


  } catch (error) {

    console.error(
      "Delete error:",
      error
    );


    alert(
      "Something went wrong while deleting."
    );

  }

}


/* =========================================================
   ESCAPE HTML
========================================================= */

function esc(value) {

  return String(
    value || ""
  )
    .replace(
      /[&<>"']/g,
      function(m) {

        return {
          "&": "&amp;",
          "<": "&lt;",
          ">": "&gt;",
          '"': "&quot;",
          "'": "&#039;"
        }[m];

      }
    );

}


/* =========================================================
   START
========================================================= */

updateContentTypeUI();

loadStudioSongs();
