const uploadForm = document.getElementById("uploadForm");
const editForm = document.getElementById("editForm");

const status = document.getElementById("status");
const editStatus = document.getElementById("editStatus");

const list = document.getElementById("adminSongs");

const editPanel = document.getElementById("editPanel");
const cancelEdit = document.getElementById("cancelEdit");

let songs = [];


/* =========================================================
   LOAD TUS LIBRARY
========================================================= */

function loadTusLibrary() {

  return new Promise((resolve, reject) => {

    if (window.tus) {
      resolve();
      return;
    }

    const existing =
      document.querySelector(
        'script[data-hh-tus="true"]'
      );

    if (existing) {

      existing.addEventListener(
        "load",
        () => resolve()
      );

      existing.addEventListener(
        "error",
        () =>
          reject(
            new Error(
              "Unable to load upload library."
            )
          )
      );

      return;
    }

    const script =
      document.createElement("script");

    script.src =
      "https://cdn.jsdelivr.net/npm/tus-js-client@4.3.1/dist/tus.min.js";

    script.async = true;

    script.dataset.hhTus = "true";

    script.onload = () => {

      if (window.tus) {
        resolve();
      } else {
        reject(
          new Error(
            "Upload library loaded incorrectly."
          )
        );
      }

    };

    script.onerror = () => {

      reject(
        new Error(
          "Unable to load secure upload library. Please check your internet connection."
        )
      );

    };

    document.head.appendChild(script);

  });

}


/* =========================================================
   PROGRESS UI
========================================================= */

function createProgressUI(container) {

  let box =
    container.querySelector(
      ".hh-progress"
    );

  if (!box) {

    box =
      document.createElement("div");

    box.className =
      "hh-progress";

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
    container.querySelector(
      ".hh-progress"
    );

  if (!box) return;

  const bar =
    box.querySelector(
      ".hh-progress-bar"
    );

  const percentText =
    box.querySelector(
      ".hh-progress-percent"
    );

  const stageText =
    box.querySelector(
      ".hh-progress-stage"
    );

  const messageText =
    box.querySelector(
      ".hh-progress-message"
    );


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
    container.querySelector(
      ".hh-progress"
    );

  if (!box) return;

  const bar =
    box.querySelector(
      ".hh-progress-bar"
    );

  const percentText =
    box.querySelector(
      ".hh-progress-percent"
    );

  const stageText =
    box.querySelector(
      ".hh-progress-stage"
    );

  const messageText =
    box.querySelector(
      ".hh-progress-message"
    );


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

    box.classList.add(
      "success"
    );

  } else {

    stageText.textContent =
      "Upload failed";

    messageText.textContent =
      message ||
      "Please try again.";

    box.classList.add(
      "error"
    );

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

    }

  `;

  document.head.appendChild(
    style
  );

})();


/* =========================================================
   GET SIGNED UPLOAD URL
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

        body: JSON.stringify({

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
      "Unable to prepare secure upload."
    );

  }


  if (
    !data.token ||
    !data.endpoint ||
    !data.path
  ) {

    throw new Error(
      "Server returned an incomplete upload configuration."
    );

  }


  return data;

}


/* =========================================================
   DIRECT SUPABASE TUS UPLOAD
========================================================= */

function uploadToSupabase(
  file,
  uploadInfo,
  onProgress
) {

  return new Promise(
    (resolve, reject) => {

      if (!window.tus) {

        reject(
          new Error(
            "Secure upload library is not available."
          )
        );

        return;
      }


      const upload =
        new tus.Upload(
          file,
          {

            endpoint:
              uploadInfo.endpoint,

            chunkSize:
              6 * 1024 * 1024,

            retryDelays: [
              0,
              3000,
              5000,
              10000,
              20000
            ],

            headers: {

              "x-signature":
                uploadInfo.token

            },

            metadata: {

              bucketName:
                uploadInfo.bucket,

              objectName:
                uploadInfo.path,

              contentType:
                uploadInfo.content_type ||
                file.type ||
                "application/octet-stream"

            },

            uploadDataDuringCreation:
              true,

            removeFingerprintOnSuccess:
              true,


            onError(error) {

              console.error(
                "TUS upload error:",
                error
              );

              reject(
                new Error(
                  error?.message ||
                  "File upload failed."
                )
              );

            },


            onProgress(
              bytesUploaded,
              bytesTotal
            ) {

              if (
                !bytesTotal
              ) {
                return;
              }

              const percent =
                (
                  bytesUploaded /
                  bytesTotal
                ) * 100;


              onProgress(
                percent,
                bytesUploaded,
                bytesTotal
              );

            },


            onSuccess() {

              resolve(
                uploadInfo
              );

            }

          }
        );


      upload.start();

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
    (percent) => {

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
   LOAD SONGS
========================================================= */

async function load() {

  try {

    const response =
      await fetch(
        "/api/songs"
      );


    if (!response.ok) {

      throw new Error(
        "Unable to load songs."
      );

    }


    songs =
      await response.json();


    renderSongs();

  } catch (error) {

    console.error(error);

    list.innerHTML =
      '<div class="empty">Unable to load songs.</div>';

  }

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
    songs.map(song => `

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

    `).join("");

}


/* =========================================================
   UPLOAD NEW SONG
========================================================= */

uploadForm.onsubmit =
  async (e) => {

    e.preventDefault();


    status.className = "";

    status.textContent = "";


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


    if (!cover) {

      status.className =
        "error";

      status.textContent =
        "Please select a cover image.";

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
      "Uploading…";


    updateProgress(
      uploadForm,
      0,
      "Preparing upload",
      "Preparing your song files…"
    );


    try {

      /*
        Load TUS once.
      */

      await loadTusLibrary();


      /*
        Upload cover first.
        0% → 15%
      */

      const coverPath =
        await uploadFile(
          cover,
          "covers",
          uploadForm,
          0,
          15,
          "Cover"
        );


      /*
        Upload audio.
        15% → 95%
      */

      const audioPath =
        await uploadFile(
          audio,
          "audio",
          uploadForm,
          15,
          80,
          "Audio"
        );


      /*
        Final database publication.
        95% → 100%
      */

      updateProgress(
        uploadForm,
        95,
        "Publishing",
        "Saving song details and finishing publication…"
      );


      const formData =
        new FormData(
          uploadForm
        );


      const metadata = {

        title:
          formData.get(
            "title"
          ),

        artist:
          formData.get(
            "artist"
          ) ||
          "Madushanka",

        language:
          formData.get(
            "language"
          ),

        genre:
          formData.get(
            "genre"
          ),

        mood:
          formData.get(
            "mood"
          ),

        description:
          formData.get(
            "description"
          ),

        lyrics:
          formData.get(
            "lyrics"
          ),

        release_date:
          formData.get(
            "release_date"
          ),

        cover_path:
          coverPath,

        audio_path:
          audioPath

      };


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
          "Song publication failed."
        );

      }


      updateProgress(
        uploadForm,
        100,
        "Published successfully",
        "Your song is now live."
      );


      finishProgress(
        uploadForm,
        true,
        "Your song has been published successfully."
      );


      status.className =
        "success";

      status.textContent =
        "Published successfully.";


      uploadForm.reset();


      if (
        uploadForm.artist
      ) {

        uploadForm.artist.value =
          "Madushanka";

      }


      await load();


    } catch (error) {

      console.error(error);


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
    songs.find(
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


  preview.src =
    song.cover_url || "";


  editStatus.className =
    "";

  editStatus.textContent =
    "";


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

cancelEdit.onclick = () => {

  editPanel.classList.add(
    "hidden"
  );


  editForm.reset();


  editStatus.className =
    "";

  editStatus.textContent =
    "";


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
  async (e) => {

    e.preventDefault();


    const id =
      document.getElementById(
        "editId"
      ).value;


    editStatus.className =
      "";

    editStatus.textContent =
      "";


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


    const progress =
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

      await loadTusLibrary();


      /*
        Upload replacement cover
        if selected.

        0% → 20%
      */

      let coverPath =
        null;


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


      /*
        Upload replacement audio
        if selected.

        If cover exists:
        20% → 95%

        If no cover:
        0% → 95%
      */

      let audioPath =
        null;


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


      /*
        No files?
        Jump to 70%.
      */

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


      /*
        Final database update.
      */

      updateProgress(
        editForm,
        95,
        "Finalizing",
        "Saving changes and updating the song…"
      );


      const formData =
        new FormData(
          editForm
        );


      const metadata = {

        title:
          formData.get(
            "title"
          ),

        artist:
          formData.get(
            "artist"
          ) ||
          "Madushanka",

        language:
          formData.get(
            "language"
          ),

        genre:
          formData.get(
            "genre"
          ),

        mood:
          formData.get(
            "mood"
          ),

        description:
          formData.get(
            "description"
          ),

        lyrics:
          formData.get(
            "lyrics"
          ),

        release_date:
          formData.get(
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
          "Update failed."
        );

      }


      finishProgress(
        editForm,
        true,
        "Your changes have been saved successfully."
      );


      editStatus.className =
        "success";

      editStatus.textContent =
        "Changes saved successfully.";


      await load();


      setTimeout(() => {

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

      }, 900);


    } catch (error) {

      console.error(error);


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
   DELETE SONG
========================================================= */

async function deleteSong(id) {

  const song =
    songs.find(
      s =>
        Number(s.id) ===
        Number(id)
    );


  const title =
    song?.title ||
    "this song";


  if (
    !confirm(
      `Delete "${title}" and its files?`
    )
  ) {
    return;
  }


  try {

    const response =
      await fetch(
        "/api/songs/" + id,
        {
          method: "DELETE"
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
        "Delete failed."
      );

      return;
    }


    await load();


  } catch (error) {

    console.error(error);

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
      m =>
        ({
          "&": "&amp;",
          "<": "&lt;",
          ">": "&gt;",
          '"': "&quot;",
          "'": "&#039;"
        }[m])
    );

}


/* =========================================================
   START
========================================================= */

load();
