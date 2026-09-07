const uploadForm = document.getElementById("uploadForm");
const editForm = document.getElementById("editForm");

const status = document.getElementById("status");
const editStatus = document.getElementById("editStatus");

const list = document.getElementById("adminSongs");

const editPanel = document.getElementById("editPanel");
const cancelEdit = document.getElementById("cancelEdit");


let songs = [];


/* =========================================================
   LOAD SONGS
========================================================= */

async function load() {

  try {

    const response = await fetch("/api/songs");

    if (!response.ok) {
      throw new Error("Unable to load songs.");
    }

    songs = await response.json();

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


  list.innerHTML = songs.map(song => `

    <div class="admin-row">

      <div class="admin-song-info">

        <img
          class="admin-thumb"
          src="${song.cover_url || ""}"
          alt=""
        >

        <div>

          <b>${esc(song.title)}</b>

          <small class="meta">
            ${esc(song.artist || "Madushanka")}
            •
            ${esc(song.genre || "")}
            •
            ${esc(song.language || "")}
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

uploadForm.onsubmit = async (e) => {

  e.preventDefault();

  status.className = "";
  status.textContent = "Publishing…";


  try {

    const response = await fetch(
      "/api/songs",
      {
        method: "POST",
        body: new FormData(uploadForm)
      }
    );


    const data = await response.json();


    if (!response.ok) {

      status.className = "error";

      status.textContent =
        data.error || "Upload failed.";

      return;
    }


    status.className = "success";

    status.textContent =
      "Published successfully.";


    uploadForm.reset();

    uploadForm.artist.value =
      "Madushanka";


    await load();


  } catch (error) {

    console.error(error);

    status.className = "error";

    status.textContent =
      "Something went wrong. Please try again.";
  }
};


/* =========================================================
   OPEN EDIT FORM
========================================================= */

function editSong(id) {

  const song =
    songs.find(s => Number(s.id) === Number(id));


  if (!song) return;


  document.getElementById("editId").value =
    song.id;

  document.getElementById("editTitle").value =
    song.title || "";

  document.getElementById("editArtist").value =
    song.artist || "Madushanka";

  document.getElementById("editLanguage").value =
    song.language || "Sinhala";

  document.getElementById("editGenre").value =
    song.genre || "Romantic";

  document.getElementById("editMood").value =
    song.mood || "";

  document.getElementById("editDescription").value =
    song.description || "";

  document.getElementById("editLyrics").value =
    song.lyrics || "";

  document.getElementById("editReleaseDate").value =
    song.release_date || "";


  const preview =
    document.getElementById("editCoverPreview");


  preview.src =
    song.cover_url || "";


  editStatus.className = "";
  editStatus.textContent = "";


  editPanel.classList.remove("hidden");


  editPanel.scrollIntoView({
    behavior: "smooth",
    block: "start"
  });
}


/* =========================================================
   CANCEL EDIT
========================================================= */

cancelEdit.onclick = () => {

  editPanel.classList.add("hidden");

  editForm.reset();

  editStatus.className = "";
  editStatus.textContent = "";
};


/* =========================================================
   SAVE EDIT
========================================================= */

editForm.onsubmit = async (e) => {

  e.preventDefault();


  const id =
    document.getElementById("editId").value;


  editStatus.className = "";

  editStatus.textContent =
    "Saving changes…";


  try {

    const response = await fetch(
      "/api/songs/" + id,
      {
        method: "PUT",
        body: new FormData(editForm)
      }
    );


    const data =
      await response.json();


    if (!response.ok) {

      editStatus.className = "error";

      editStatus.textContent =
        data.error || "Update failed.";

      return;
    }


    editStatus.className = "success";

    editStatus.textContent =
      "Changes saved successfully.";


    await load();


    setTimeout(() => {

      editPanel.classList.add("hidden");

      editForm.reset();

      editStatus.textContent = "";

    }, 700);


  } catch (error) {

    console.error(error);

    editStatus.className = "error";

    editStatus.textContent =
      "Something went wrong. Please try again.";
  }
};


/* =========================================================
   DELETE SONG
========================================================= */

async function deleteSong(id) {

  const song =
    songs.find(s => Number(s.id) === Number(id));


  const title =
    song?.title || "this song";


  if (
    !confirm(
      `Delete "${title}" and its files?`
    )
  ) {
    return;
  }


  try {

    const response = await fetch(
      "/api/songs/" + id,
      {
        method: "DELETE"
      }
    );


    const data =
      await response.json();


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

  return String(value || "")
    .replace(
      /[&<>"']/g,
      m => ({
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
