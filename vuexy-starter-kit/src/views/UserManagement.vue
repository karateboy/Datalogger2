<template>
  <b-card>
    <b-table
      :items="userList"
      :fields="fields"
      select-mode="single"
      selectable
      responsive
      @row-selected="onUserSelected"
    >
      <template v-slot:cell(selected)="{ rowSelected }">
        <template v-if="rowSelected">
          <span aria-hidden="true">&check;</span>
          <span class="sr-only">Selected</span>
        </template>
        <template v-else>
          <span aria-hidden="true">&nbsp;</span>
          <span class="sr-only">Not selected</span>
        </template>
      </template>
      <template v-slot:custom-foot>
        <b-tr>
          <b-td colspan="3">
            <b-button
              v-ripple.400="'rgba(255, 255, 255, 0.15)'"
              variant="primary"
              class="mr-1"
              @click="newUser"
            >
              新增
            </b-button>
            <b-button
              v-ripple.400="'rgba(255, 255, 255, 0.15)'"
              variant="primary"
              class="mr-1"
              @click="updateUser"
            >
              更新
            </b-button>
            <b-button
              v-ripple.400="'rgba(255, 255, 255, 0.15)'"
              variant="primary"
              class="mr-1"
              @click="deleteUser"
            >
              刪除
            </b-button>
          </b-td>
        </b-tr>
      </template>
    </b-table>

    <b-modal
      id="userModal"
      :title="modalTitle"
      modal-class="modal-primary"
      hide-footer
      size="lg"
    >
      <user
        :is-new="isNew"
        :current-user="user"
        @updated="onRefresh"
        @created="onRefresh"
      ></user>
    </b-modal>
  </b-card>
</template>
<script>
import Vue from 'vue';
import axios from 'axios';
import Ripple from 'vue-ripple-directive';
import User from './User.vue';

export default Vue.extend({
  components: {
    User,
  },
  directives: {
    Ripple,
  },
  data() {
    const userList = [];
    const fields = [
      {
        key: 'selected',
        label: '選擇',
      },
      {
        key: '_id',
        label: '帳號',
        sortable: true,
      },
      {
        key: 'name',
        label: '顯示名稱',
        sortable: true,
      },
      {
        key: 'isAdmin',
        label: '管理員',
        sortable: true,
      },
    ];

    return {
      fields,
      userList,
      isNew: true,
      selected: [],
    };
  },
  computed: {
    modalTitle() {
      if (this.isNew) return '新增使用者';

      return '更新使用者';
    },
    user() {
      return this.selected[0];
    },
  },
  mounted() {
    this.getUserList();
  },

  methods: {
    newUser() {
      this.isNew = true;
      this.$bvModal.show('userModal');
    },
    updateUser() {
      this.isNew = false;
      this.$bvModal.show('userModal');
    },
    deleteUser() {
      this.$bvModal
        .msgBoxConfirm('是否要刪除使用者?')
        .then(ret => {
          if (ret) {
            this.delUser(this.user.Id);
          }
        })
        .catch(err => {
          throw Error(err);
        });
    },
    getUserList() {
      axios
        .get('/User')
        .then(res => {
          const ret = res.data;

          for (const usr of ret) {
            this.userList.push(usr);
          }
        })
        .catch(err => {
          throw new Error(err);
        });
    },
    onUserSelected(items) {
      this.selected = items;
    },
    delUser(id) {
      axios
        .delete(`/User/${id}`)
        .then(() => {
          this.getUserList();
        })
        .catch(err => {
          throw new Error(err);
        });
    },
    onRefresh() {
      this.$bvModal.hide('userModal');
      this.getUserList();
    },
  },
});
</script>
